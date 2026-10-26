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
  if(args.length==1&&args[0].equals("--self-test")){SelfTest.main(new String[0]);return 0;}
  if(args.length==2&&args[0].equals("--conformance"))return ConformanceRunner.run(Path.of(args[1]));
  byte[] schema=resource("greeter.schema.json");Registry registry=new Registry();List<String> greetings=new ArrayList<>();
  registry.function("check",List.of("bool"),"void",(ctx,self,a)->{if(!a[0].asBool())throw VmException.host("check failed");return Value.none();});
  registry.function("greet",List.of("string"),"void",(ctx,self,a)->{String line="Hello, "+a[0].asString()+"!";greetings.add(line);System.err.println(line);return Value.none();});
  if(args.length>=1&&args[0].equals("--dsl-schema")){System.out.write(schema);return 0;}
  if(args.length==1&&args[0].equals("--dsl-signatures")){registry.signatures().forEach(System.out::println);return 0;}
  if(args.length!=2||!args[0].equals("--dsl")){System.err.println("usage: lmcode-java --version | --notices | --dsl-schema [--json] | --dsl-signatures | --dsl FILE");return 2;}
  String phase="load";try{io.lmctl.lmcode.Program program=io.lmctl.lmcode.Program.load(Files.readAllBytes(Path.of(args[1])),schema,registry);phase="run";Context context=new Context();String cap=System.getenv("LMCODE_JAVA_MAX_MEMORY_MB");if(cap!=null){try{long mb=Long.parseLong(cap);if(mb<=0||mb>Long.MAX_VALUE/(1024*1024))throw new NumberFormatException();context.memoryLimitBytes=mb*1024*1024;}catch(NumberFormatException e){System.err.println("LMCODE_JAVA_MAX_MEMORY_MB must be positive whole MiB");return 2;}}program.run(context,registry);return Reports.ok("["+String.join(",",greetings.stream().map(Reports::escape).toList())+"]");}catch(VmException e){return Reports.error(phase,e);}catch(IOException e){return Reports.error(phase,new VmException(phase.equals("load")?"LOAD_BAD_BYTECODE":"RUN_HOST",e.getMessage()));}
 }
 public static void main(String[] args)throws IOException {int status=run(args);if(status!=0)System.exit(status);}
}
