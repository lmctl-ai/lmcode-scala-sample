package io.lmctl.lmcode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Reference frame-fixture HOST, not automatic SDK emission or a sample cutover. */
public final class ReturnConformanceRunner {
 private ReturnConformanceRunner(){}
 public record Result(int exit,byte[] stdout,byte[] stderr){}
 static Result execute(Path binary,Path schema,int repeat,boolean reset,boolean early,boolean rounding,boolean appFail){
  ByteArrayOutputStream output=new ByteArrayOutputStream();
  try{
   Registry registry=new Registry();ResultSink sink=new ResultSink();registry.setResultSink(sink);
   registry.function("entry_fail",List.of(),"void",(c,s,a)->{throw VmException.host("entry failure");});
   registry.function("expect_result",List.of("string"),"void",(c,s,a)->{
    if(!sink.json().equals(a[0].asString().toString()))throw VmException.host("return overload payload mismatch");return Value.none();
   });
   Program program=Program.load(Files.readAllBytes(binary),Files.readAllBytes(schema),registry);Context context=new Context();
   sink.reset();program.run(context,registry);
   if(early)Reports.writeResultFrame(output,sink); // fault host: emits before selected entries can fail
   for(int n=0;n<repeat;n++)for(int i=0;i<program.entries().size();i++){
    if(reset)sink.reset(); // fault host: erases body/prior entry writes
    Value value=program.call(i,context,registry,List.of());
    if(value.kind!=Value.Kind.INT||value.asInt()!=7)throw VmException.host("entry HALT result changed");
   }
   if(appFail)return new Result(4,output.toByteArray(),new byte[0]);
   if(rounding){
    String bad=Formatting.formatG15(Double.parseDouble(sink.json()));
    output.writeBytes(("LMCODE_RETURN_BEGIN\n"+bad+"\nLMCODE_RETURN_END\n").getBytes(StandardCharsets.UTF_8));
   }else Reports.writeResultFrame(output,sink);
   return new Result(0,output.toByteArray(),new byte[0]);
  }catch(VmException|IOException error){
   return new Result(3,output.toByteArray(),(error.getMessage()+"\n").getBytes(StandardCharsets.UTF_8));
  }
 }
 public static int run(Path directory)throws Exception{
  // Canonical hashes bind bytes and expectations; never regenerate expected output here.
  for(String line:Files.readAllLines(directory.resolve("vectors.lock"))){
   String[] fields=line.split("  ",2);if(fields.length!=2)throw new AssertionError("invalid return fixture lock");
   String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(directory.resolve(fields[1]))));
   if(!hash.equals(fields[0]))throw new AssertionError("modified return fixture "+fields[1]);
  }
  Map<String,Object> manifest=Json.object(Json.parse(Files.readAllBytes(directory.resolve("manifest.json"))));
  int count=0;
  for(Object item:Json.array(manifest.get("vectors"))){
   Map<String,Object> vector=Json.object(item),expect=Json.object(vector.get("expect"));
   String name=(String)vector.get("name");
   Result result=execute(directory.resolve((String)vector.get("bytecode")),directory.resolve((String)manifest.get("schema")),((Number)vector.get("repeat")).intValue(),false,false,false,false);
   byte[] expected=Files.readAllBytes(directory.resolve((String)expect.get("file")));
   if(result.exit()!=((Number)expect.get("exit")).intValue()||!Arrays.equals(result.stdout(),expected))
    throw new AssertionError("return fixture "+name+": exit="+result.exit()+" stdout="+new String(result.stdout(),StandardCharsets.UTF_8)+" stderr="+new String(result.stderr(),StandardCharsets.UTF_8));
   System.out.println("PASS return fixture "+name);count++;
  }
  if(count!=9)throw new AssertionError("expected nine canonical return fixtures, got "+count);
  // Each deliberate host bug must break the corresponding exact stdout/exit oracle.
  Path schema=directory.resolve("schema.json");
  control(directory,"float-roundtrip",execute(directory.resolve("float-roundtrip.dslb"),schema,1,false,false,true,false),0);
  control(directory,"entry-sink",execute(directory.resolve("entry-sink.dslb"),schema,1,true,false,false,false),0);
  control(directory,"entry-error",execute(directory.resolve("entry-error.dslb"),schema,1,false,true,false,false),3);
  Result app=execute(directory.resolve("float-roundtrip.dslb"),schema,1,false,false,false,true);
  if(app.exit()!=4||app.stdout().length!=0)throw new AssertionError("application failure emitted a frame");
  return 0;
 }
 private static void control(Path directory,String name,Result mutated,int code)throws IOException{
  if(mutated.exit()!=code)throw new AssertionError("wrong mutation failure: "+name+" exit "+mutated.exit());
  byte[] expected=Files.readAllBytes(directory.resolve(name+".expect"));
  if(Arrays.equals(mutated.stdout(),expected))throw new AssertionError("SURVIVED return host mutation "+name);
  System.out.println("CAUGHT return host mutation "+name);
 }
 public static void main(String[] args)throws Exception{
  if(args.length==1){run(Path.of(args[0]));return;}
  if(args.length<2)throw new IllegalArgumentException("return runner needs BYTECODE SCHEMA [flags]");
  int repeat=1;boolean reset=false,early=false,rounding=false,app=false;
  for(int i=2;i<args.length;i++)switch(args[i]){
   case "--repeat"->repeat=Integer.parseInt(args[++i]);case "--mutate-reset"->reset=true;
   case "--mutate-early-frame"->early=true;case "--mutate-rounding"->rounding=true;case "--app-fail"->app=true;
   default->throw new IllegalArgumentException("unknown return runner flag");
  }
  Result result=execute(Path.of(args[0]),Path.of(args[1]),repeat,reset,early,rounding,app);
  System.out.write(result.stdout());System.err.write(result.stderr());System.out.flush();System.err.flush();
  if(System.out.checkError()||System.err.checkError())System.exit(3);
  if(result.exit()!=0)System.exit(result.exit());
 }
}
