package io.lmctl.lmcode;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** One-process adapter for the canonical native invocation goldens. */
public final class InvocationConformanceRunner {
 private InvocationConformanceRunner(){}
 private static byte[] read(String path){try{return Files.readAllBytes(Path.of(path));}catch(IOException e){throw new VmException("LOAD_BAD_BYTECODE","cannot read fixture");}}
 public static int run(String[] args,OutputStream output){
  if(args.length<3)return Reports.failureFrame(output,"cli",new VmException("CLI_USAGE","fixture arguments required"));
  try{
   Map<String,Object> config=Json.object(InvocationJson.owned(read(args[1])));List<String> files=List.of(Arrays.copyOfRange(args,2,args.length));
   InvocationResults results=InvocationResults.fromCommand(files);Registry registry=new Registry();Context context=new Context();context.setDry(results.dry());long[] counter={0};
   registry.function("next_counter",List.of(),"int",(c,s,a)->Value.ofInt(++counter[0]));registry.function("entry_fail",List.of(),"void",(c,s,a)->{throw VmException.host("native entry failure");},Registry.dry((c,s,a)->{throw VmException.host("preview failed");}));
   byte[] schema=read(args[0]);ResultContract contract=ResultContract.fromSchema(schema);
   for(Object raw:Json.array(config.get("execution"))){int index=((Number)raw).intValue();String phase="load";
    try{
     InvocationResults.Registration registration=results.registrations().stream().filter(p->p.index()==index).findFirst().orElseThrow(()->new VmException("CLI_USAGE","fixture index absent"));
     Program program=Program.load(read(registration.file()),schema,registry);results.select(index,registry,contract);phase="run";program.run(context,registry);
     for(int i=0;i<program.entries().size();i++){Value value=program.call(i,context,registry,List.of());if(value.kind!=Value.Kind.INT||value.asInt()!=7)throw VmException.host("entry HALT result changed");}
     results.complete(index);
    }catch(VmException e){results.fail(index,phase,e);break;}
   }
   if(Boolean.TRUE.equals(config.get("app_fail")))results.fail(null,"app",new VmException("APP_FAIL","native validation failed"));
   return results.writeFrame(output);
  }catch(VmException e){return Reports.failureFrame(output,"cli",e);}
 }
 public static void main(String[] args){int status=run(args,System.out);if(status!=0)System.exit(status);}
}
