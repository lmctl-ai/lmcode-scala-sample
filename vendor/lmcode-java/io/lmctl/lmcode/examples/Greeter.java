package io.lmctl.lmcode.examples;
import io.lmctl.lmcode.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
/** Minimal typed Java host, also exposing the same test entry points in native builds. */
public final class Greeter {
 public static final String VERSION="0.1.1";
 private static byte[] resource(String name)throws IOException {try(InputStream in=Greeter.class.getResourceAsStream("/"+name)){if(in==null)throw new IOException("missing embedded "+name);return in.readAllBytes();}}
 public static int run(String[] args)throws IOException {
  if(args.length==1&&args[0].equals("--version")){System.out.println("lmcode-java "+VERSION);return 0;}
  if(args.length==1&&args[0].equals("--notices")){System.out.write(resource("notices.txt"));return 0;}
  if(args.length>=1&&args[0].equals("--invocation-fixture"))return InvocationConformanceRunner.run(Arrays.copyOfRange(args,1,args.length),System.out);
  if(args.length==1&&args[0].equals("--invocation-self-test")){InvocationChecks.run("");return 0;}
  if(args.length==1&&args[0].equals("--self-test")){SelfTest.main(new String[0]);return 0;}
  if(args.length>=3&&args[0].equals("--return-fixture")){try{ReturnConformanceRunner.main(Arrays.copyOfRange(args,1,args.length));return 0;}catch(Exception e){throw new IOException(e);}}
  if(args.length==2&&args[0].equals("--return-conformance")){try{return ReturnConformanceRunner.run(Path.of(args[1]));}catch(Exception e){throw new IOException(e);}}
  if(args.length==2&&args[0].equals("--conformance"))return ConformanceRunner.run(Path.of(args[1]));
  byte[] schema=resource("greeter.schema.json");Registry registry=new Registry();List<String> greetings=new ArrayList<>();
  registry.function("check",List.of("bool"),"void",(ctx,self,a)->{if(!a[0].asBool())throw VmException.host("check failed");return Value.none();});
  registry.function("greet",List.of("string"),"void",(ctx,self,a)->{String line="Hello, "+a[0].asString()+"!";greetings.add(line);System.err.println(line);return Value.none();});
  if(args.length>=1&&args[0].equals("--lmcode-schema")){System.out.write(schema);return 0;}
  if(args.length==1&&args[0].equals("--lmcode-signatures")){registry.signatures().forEach(System.out::println);return 0;}
  if(args.length!=2||!args[0].equals("--lmcode")){return Reports.failureFrame("cli",new VmException("CLI_USAGE","usage: lmcode-java --version | --notices | --lmcode-schema [--json] | --lmcode-signatures | --lmcode FILE"));}
  InvocationResults results;try{results=InvocationResults.fromCommand(List.of(args[1]));}catch(VmException e){return Reports.failureFrame("cli",e);}
  String phase="load";try{results.select(0,registry,ResultContract.fromSchema(schema));io.lmctl.lmcode.Program program=io.lmctl.lmcode.Program.load(Files.readAllBytes(Path.of(args[1])),schema,registry);phase="run";Context context=new Context();String cap=System.getenv("LMCODE_JAVA_MAX_MEMORY_MB");if(cap!=null){try{long mb=Long.parseLong(cap);if(mb<=0||mb>Long.MAX_VALUE/(1024*1024))throw new NumberFormatException();context.memoryLimitBytes=mb*1024*1024;}catch(NumberFormatException e){return Reports.failureFrame("cli",new VmException("CLI_USAGE","LMCODE_JAVA_MAX_MEMORY_MB must be positive whole MiB"));}}program.run(context,registry);results.complete(0);return results.writeFrame(System.out);}catch(VmException e){results.fail(0,phase,e);return results.writeFrame(System.out);}catch(IOException e){results.fail(0,phase,new VmException(phase.equals("load")?"LOAD_BAD_BYTECODE":"RUN_HOST",e.getMessage()));return results.writeFrame(System.out);}
 }
 public static void main(String[] args)throws IOException {int status=run(args);if(status!=0)System.exit(status);}
}
