package io.lmctl.lmcode;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Per-command accumulation. Hosts serialize access and drain their own active work. */
public final class InvocationResults {
 public record Registration(String file,int index){}
 private static final class Slot {ResultSink sink;Object result;boolean completed;}
 private record Failure(Integer part,String phase,VmException error){}
 private final boolean builder;
 private final List<Registration> registrations;
 private final Slot[] slots;
 private Failure failure;
 private boolean writing;
 private InvocationResults(boolean builder,List<Registration> registrations,int count){
  this.builder=builder;this.registrations=List.copyOf(registrations);slots=new Slot[count];for(int i=0;i<count;i++)slots[i]=new Slot();
 }
 static VmException metadataError(String message){return new VmException("CLI_USAGE","lmcode invocation: "+message);}
 private static Map<String,Object> keys(Object value,Set<String> allowed){
  if(!(value instanceof Map<?,?>))throw metadataError("object required");
  Map<String,Object> map=Json.object(value);for(String key:map.keySet())if(!allowed.contains(key))throw metadataError("unknown key "+key);return map;
 }
 public static InvocationResults fromMetadata(String raw,List<String> files){try{return fromMetadata(InvocationJson.utf8(raw),files);}catch(VmException e){if(e.code.equals("CLI_USAGE"))throw e;throw metadataError(e.getMessage());}}
 public static InvocationResults fromMetadata(byte[] raw,List<String> parsedFiles){
  try{
   Map<String,Object> root=keys(InvocationJson.owned(raw),Set.of("version","mode","parts"));Object version=root.get("version"),mode=root.get("mode"),rawParts=root.get("parts");
   if(!InvocationJson.integral(version)||((Number)version).longValue()!=1)throw metadataError("unsupported version");
   if(!Objects.equals(mode,"single")&&!Objects.equals(mode,"builder"))throw metadataError("invalid mode");
   if(!(rawParts instanceof List<?> parts)||parts.isEmpty()||mode.equals("single")&&parts.size()!=1)throw metadataError("invalid parts");
   List<String> files=new ArrayList<>();
   for(int i=0;i<parts.size();i++){
    Map<String,Object> part=keys(parts.get(i),Set.of("index","file"));Object index=part.get("index"),rawFile=part.get("file");
    if(!InvocationJson.integral(index)||((Number)index).longValue()!=i)throw metadataError("noncontiguous index");
    if(!(rawFile instanceof String file)||file.indexOf('\0')>=0||!Path.of(file).isAbsolute())throw metadataError("file must be absolute");
    if(files.contains(file))throw metadataError("duplicate file");files.add(file);
   }
   if(parsedFiles.size()!=files.size())throw metadataError("registration count mismatch");
   List<Registration> registrations=new ArrayList<>();Set<Integer> seen=new HashSet<>();
   for(String file:parsedFiles){int index=files.indexOf(file);if(index<0)throw metadataError("extra registration");if(!seen.add(index))throw metadataError("duplicate registration");registrations.add(new Registration(file,index));}
   return new InvocationResults(mode.equals("builder"),registrations,files.size());
  }catch(VmException e){if(e.code.equals("CLI_USAGE"))throw e;throw metadataError(e.getMessage());}
  catch(IllegalArgumentException e){throw metadataError(e.getMessage());}
 }
 public static InvocationResults fromCommand(List<String> parsedFiles){return fromCommand(parsedFiles,System.getenv("LMCODE_INVOCATION_FILE"));}
 /** Explicit manifest path is for native embedding/tests, not a public host flag. Null means absent. */
 public static InvocationResults fromCommand(List<String> parsedFiles,String manifest){
  if(manifest!=null){
   try{
    InvocationJson.utf8(manifest);
    if(manifest.isEmpty()||manifest.indexOf('\0')>=0||!Path.of(manifest).isAbsolute())throw metadataError("manifest path must be absolute");
    return fromMetadata(Files.readAllBytes(Path.of(manifest)),parsedFiles);
   }catch(IOException e){throw metadataError("cannot read manifest");}
   catch(VmException e){if(e.code.equals("CLI_USAGE"))throw e;throw metadataError(e.getMessage());}
   catch(IllegalArgumentException e){throw metadataError("manifest path must be absolute");}
  }
  if(parsedFiles.isEmpty())throw metadataError("empty parts");List<Registration> registrations=new ArrayList<>();
  for(int i=0;i<parsedFiles.size();i++){
   String file=parsedFiles.get(i);if(file==null||file.isEmpty()||file.indexOf('\0')>=0)throw metadataError("invalid command file");
   try{InvocationJson.utf8(file);}catch(VmException e){throw metadataError("file must be UTF-8");}
   registrations.add(new Registration(file,i));
  }
  return new InvocationResults(parsedFiles.size()>1,registrations,parsedFiles.size());
 }
 public List<Registration> registrations(){return registrations;}
 private Slot slot(int index){if(index<0||index>=slots.length)throw VmException.host("lmcode invocation: invalid slot index");return slots[index];}
 private void active(){if(writing)throw VmException.host("lmcode invocation: finalized");}
 public ResultSink select(int index,Registry registry,ResultContract contract){
  active();Slot slot=slot(index);
  if(slot.sink==null){if(failure!=null)throw VmException.host("lmcode invocation: cannot start work after failure");slot.sink=new ResultSink(contract);}
  if(slot.sink.isSealed())throw VmException.host("lmcode return: result sink is sealed");
  if(!slot.sink.contract().equals(contract))throw VmException.host("lmcode invocation: result contract changed");
  registry.setResultSink(slot.sink);return slot.sink;
 }
 public void complete(int index){
  active();Slot slot=slot(index);if(slot.sink==null)throw VmException.host("lmcode invocation: slot was not selected");
  try{slot.result=slot.sink.seal();slot.completed=true;}catch(VmException e){fail(index,"run",e);throw e;}
 }
 public void fail(Integer index,String phase,VmException error){
  active();if(failure!=null)return;if(index!=null)slot(index);
  Reports.failureJson(phase,error,null); // validate before latching, including locations/text
  VmException copy=new VmException(error.code,error.getMessage());copy.line=error.line;copy.column=error.column;
  failure=new Failure(builder?index:null,phase,copy);
 }
 /** Finalizes once, including on write/flush failure. Never closes the caller's stream. */
 public int writeFrame(OutputStream output){
  if(writing)return 4;
  try{
   if(failure==null)for(int i=0;i<slots.length;i++)if(!slots[i].completed){fail(i,"run",VmException.host("lmcode invocation: slot not completed"));break;}
   String json;int code=0;
   if(failure!=null){json=Reports.failureJson(failure.phase,failure.error,failure.part);code=Reports.exitCode(failure.phase);}
   else{
    StringBuilder text=new StringBuilder("{\"ok\":true,\"result\":");
    if(builder){text.append('[');for(int i=0;i<slots.length;i++){if(i>0)text.append(',');text.append(ResultJson.encode(slots[i].result));}text.append("],\"parts\":").append(slots.length);}
    else text.append(ResultJson.encode(slots[0].result));
    json=text.append('}').toString();
   }
   writing=true;return Reports.emit(output,json,code);
  }catch(RuntimeException e){writing=true;Reports.outputError();return 4;}
 }
}
