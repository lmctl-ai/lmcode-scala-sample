package io.lmctl.lmcode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Runtime dry callback contracts and mutation oracles. */
final class DryChecks {
 private DryChecks(){}
 private static void require(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 private static void group(String name,String filter,Runnable check){if(name.contains(filter)){check.run();System.out.println("PASS "+name);}}

 static void run(String filter){
  group("dry callbacks sentinel and dispatch count",filter,DryChecks::callbacks);
  group("dry dummies are typed and fresh",filter,DryChecks::dummies);
  group("dry standard policies are explicit",filter,DryChecks::standardPolicies);
 }

 private static void callbacks(){
  Path directory;
  try{directory=Files.createTempDirectory("lmcode-java-dry-sentinel-");}
  catch(IOException e){throw new AssertionError(e);}
  Path sentinel=directory.resolve("real-callback-ran");AtomicInteger real=new AtomicInteger(),preview=new AtomicInteger();
  Registry.Callback effect=(context,self,args)->{
   real.incrementAndGet();
   try{Files.writeString(sentinel,"real",StandardCharsets.UTF_8);}catch(IOException e){throw VmException.host(e.getMessage());}
   return Value.ofBool(false);
  };
  byte[] bytecode=callFunction("fn effect() -> bool");
  try{
   Registry registrationChecks=new Registry(false);Registry.Callback present=(c,s,a)->Value.ofInt(1);
   try{registrationChecks.function("null_real",List.of(),"int",null);throw new AssertionError("null real callback accepted");}catch(VmException e){require(e.code.equals("LOAD_UNBOUND")&&e.getMessage().contains("null_real callback is empty"),"null real callback diagnostic");}
   try{registrationChecks.function("null_dry",List.of(),"int",present,Registry.dry(null));throw new AssertionError("null dry callback accepted");}catch(VmException e){require(e.code.equals("LOAD_UNBOUND")&&e.getMessage().contains("dry function null_dry callback is empty"),"null dry callback diagnostic");}
   registrationChecks.function("duplicate",List.of(),"int",present,Registry.dry(present));
   try{registrationChecks.function("duplicate",List.of(),"int",present,Registry.dry(present));throw new AssertionError("duplicate dry registration accepted");}catch(VmException e){require(e.code.equals("LOAD_UNBOUND")&&e.getMessage().contains("registered twice"),"duplicate dry registration diagnostic");}
   Registry defaults=new Registry(false);defaults.function("effect",List.of(),"bool",effect);
   Program defaultProgram=Program.load(bytecode,new byte[0],defaults);Context dry=new Context();dry.setDry(true);defaultProgram.run(dry,defaults);
   require(real.get()==0&&!Files.exists(sentinel),"dry fallback-to-real wrote the sentinel");
   require(Stdlib.dryDummy(dry,"bool").asBool(),"bool dry dummy must be true");

   Registry overridden=new Registry(false);
   Registry.Callback implementation=(context,self,args)->{preview.incrementAndGet();return Value.ofBool(false);};
   overridden.function("effect",List.of(),"bool",effect,Registry.dry(implementation));
   Program program=Program.load(bytecode,new byte[0],overridden);Context previewContext=new Context();previewContext.setDry(true);program.run(previewContext,overridden);
   require(preview.get()==1&&real.get()==0&&!Files.exists(sentinel),"dry callback was skipped, doubled or followed by real callback");
   program.run(new Context(),overridden);
   require(preview.get()==1&&real.get()==1&&Files.exists(sentinel),"real mode did not preserve the real callback");

   Registry methods=new Registry(false);AtomicInteger methodReal=new AtomicInteger(),methodDry=new AtomicInteger();
   methods.function("make",List.of(),"Thing",(c,s,a)->Value.object("Thing",new Object()));
   methods.method("Thing","read",List.of(),"int",(c,s,a)->{methodReal.incrementAndGet();return Value.ofInt(7);});
   Program methodProgram=Program.load(callFunctionAndMethod(),new byte[0],methods);Context methodDryContext=new Context();methodDryContext.setDry(true);methodProgram.run(methodDryContext,methods);
   require(methodReal.get()==0,"dry method without override invoked a real callback or dereferenced a dry null");
   Registry methodPreview=new Registry(false);methodPreview.function("make",List.of(),"Thing",(c,s,a)->Value.object("Thing",new Object()));
   methodPreview.method("Thing","read",List.of(),"int",(c,s,a)->{methodReal.incrementAndGet();return Value.ofInt(7);},Registry.dry((c,s,a)->{methodDry.incrementAndGet();return Value.ofInt(8);}));
   Program previewProgram=Program.load(callFunctionAndMethod(),new byte[0],methodPreview);Context methodPreviewContext=new Context();methodPreviewContext.setDry(true);
   try{previewProgram.run(methodPreviewContext,methodPreview);throw new AssertionError("dry null receiver reached method override");}
   catch(VmException e){require(e.code.equals("RUN_HOST")&&e.getMessage().contains("requires a concrete receiver after dry null")&&methodDry.get()==0&&methodReal.get()==0,"dry null reached a method callback: "+e.getMessage());}

   Registry invalid=new Registry(false);invalid.function("bad",List.of(),"bool",effect,Registry.dry((c,s,a)->Value.ofString("wrong")));
   Program bad=Program.load(callFunction("fn bad() -> bool"),new byte[0],invalid);Context badContext=new Context();badContext.setDry(true);
   try{bad.run(badContext,invalid);throw new AssertionError("wrong dry callback return accepted");}
   catch(VmException e){require(e.code.equals("RUN_HOST")&&e.getMessage().contains("dry callback for bool returned string"),"wrong dry callback return diagnostic: "+e.getMessage());}
  }finally{try{Files.deleteIfExists(sentinel);Files.deleteIfExists(directory);}catch(IOException e){throw new AssertionError(e);}}
 }

 private static void dummies(){
  Registry registry=new Registry();Context context=new Context();registry.enterRun(context);
  try{
   require(Stdlib.dryDummy(context,"bool").asBool(),"bool dummy");
   require(Stdlib.dryDummy(context,"int").asInt()==0&&Stdlib.dryDummy(context,"integer").asInt()==0&&Stdlib.dryDummy(context,"int64").asInt()==0,"integer alias dummy");
   require(Stdlib.dryDummy(context,"float").asFloat()==0&&Stdlib.dryDummy(context,"double").asFloat()==0&&Stdlib.dryDummy(context,"number").asFloat()==0,"float alias dummy");
   require(Stdlib.dryDummy(context,"string").asString().length()==0,"string dummy");
   require(Stdlib.dryDummy(context,"void").kind==Value.Kind.NONE,"void dummy must be stack neutral");
   Value nil=Stdlib.dryDummy(context,"null"),opaque=Stdlib.dryDummy(context,"Opaque");
   require(nil.kind==Value.Kind.DRY_NULL&&nil.isDryNull()&&nil.objectType().equals("null"),"null dummy provenance");
   require(opaque.kind==Value.Kind.DRY_NULL&&opaque.isDryNull()&&opaque.objectType().equals("Opaque"),"opaque dummy provenance");
   Value a=Stdlib.dryDummy(context,"IntList"),b=Stdlib.dryDummy(context,"IntList");
   require(a.asObject()!=b.asObject()&&StdCollections.unwrap(a)!=StdCollections.unwrap(b),"shared mutable IntList dummy");
   ((List<Long>)StdCollections.unwrap(a)).add(9L);
   require(((List<?>)StdCollections.unwrap(b)).isEmpty(),"mutating one dummy changed another");
   Value mapA=Stdlib.dryDummy(context,"IntMap"),mapB=Stdlib.dryDummy(context,"IntMap");
   require(mapA.asObject()!=mapB.asObject()&&StdCollections.unwrap(mapA)!=StdCollections.unwrap(mapB)&&((Map<?,?>)StdCollections.unwrap(mapB)).isEmpty(),"shared mutable IntMap dummy");
   require(((List<?>)StdCollections.unwrap(Stdlib.dryDummy(context,"StrList"))).isEmpty()&&((List<?>)StdCollections.unwrap(Stdlib.dryDummy(context,"FloatList"))).isEmpty(),"typed list dummy is not empty");
   require(((Map<?,?>)StdCollections.unwrap(Stdlib.dryDummy(context,"StrMap"))).isEmpty()&&((Map<?,?>)StdCollections.unwrap(Stdlib.dryDummy(context,"FloatMap"))).isEmpty(),"typed map dummy is not empty");
   ResultSink required=new ResultSink(ResultContract.fromSchema("{\"dsl.schema\":2,\"result\":{\"type\":\"Json\"}}"));
   try{required.set(opaque);throw new AssertionError("dry null escaped a nonnullable result contract");}catch(VmException e){require(e.code.equals("RUN_HOST")&&e.getMessage().contains("expected Json; got null"),"dry null contract diagnostic: "+e.getMessage());}
   ResultSink nullable=new ResultSink(ResultContract.fromSchema("{\"dsl.schema\":2,\"result\":{\"type\":\"Json\",\"nullable\":true}}"));nullable.set(opaque);require(nullable.json().equals("null"),"nullable dry null was not retained as JSON null");
  }finally{registry.exitRun(context);}
 }

 private static void standardPolicies(){
  Registry registry=new Registry();Set<String> denied=Set.of("fn file_write(string, string) -> void","fn file_append(string, string) -> void");
  for(String signature:registry.stdSignatures()){
   Registry.Binding binding=registry.resolve(signature);
   require(binding!=null,"missing standard binding "+signature);
   require((binding.dryCallback==null)==denied.contains(signature),"standard dry policy mismatch for "+signature);
  }
  require(registry.resolve("fn file_read(string) -> string").dryCallback.callback==registry.resolve("fn file_read(string) -> string").callback,"file_read dry(real) did not retain the real reader");
  require(registry.resolve("fn file_write(string, string) -> void").dryCallback==null&&registry.resolve("fn file_append(string, string) -> void").dryCallback==null,"file writes must be default-denied");
  Path file;
  try{file=Files.createTempFile("lmcode-java-dry-read-",".txt");Files.writeString(file,"offline fixture",StandardCharsets.UTF_8);}
  catch(IOException e){throw new AssertionError(e);}
  try{Context ctx=new Context();Value read=registry.resolve("fn file_read(string) -> string").dryCallback.callback.call(ctx,Value.none(),new Value[]{Value.ofString(file.toString())});require(read.asString().toString().equals("offline fixture"),"dry(real) fixture read changed");}
  finally{try{Files.deleteIfExists(file);}catch(IOException e){throw new AssertionError(e);}}
  Registry testing=new Registry(false);Stdlib.registerTesting(testing,null);
  require(testing.resolve("fn print(string) -> void").dryCallback!=null&&testing.resolve("fn assert_close(float, float, float) -> void").dryCallback!=null,"testing diagnostics lack dry policies");
 }

 private static byte[] callFunction(String signature){return bytecode(List.of(signature),new byte[]{7,0,0,0,16,17},1);}
 private static byte[] callFunctionAndMethod(){return bytecode(List.of("fn make() -> Thing","method Thing.read() -> int"),new byte[]{7,0,0,0,8,0,1,0,16,17},1);}
 private static byte[] bytecode(List<String> imports,byte[] code,int maxStack){
  try{
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);byte[] emptySchemaHash=MessageDigest.getInstance("SHA-256").digest(new byte[0]);
   out.writeBytes("DSLB");out.writeShort(Program.VERSION);out.write(emptySchemaHash);out.writeInt(imports.size());
   for(String signature:imports){byte[] raw=signature.getBytes(StandardCharsets.UTF_8);out.writeByte(1);out.writeInt(raw.length);out.write(raw);}
   out.writeShort(imports.size());for(int i=0;i<imports.size();i++)out.writeInt(i);out.writeShort(0);out.writeShort(0);out.writeShort(maxStack);out.writeInt(code.length);out.write(code);out.writeInt(0);out.writeShort(0);out.flush();return bytes.toByteArray();
  }catch(Exception e){throw new AssertionError(e);}
 }
}
