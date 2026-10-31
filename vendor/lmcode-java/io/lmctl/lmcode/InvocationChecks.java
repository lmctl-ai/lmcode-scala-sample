package io.lmctl.lmcode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Native-reachable assertions and compiled-fault oracles for the invocation boundary. */
public final class InvocationChecks {
 private InvocationChecks(){}
 static void equal(Object got,Object want,String why){if(!Objects.equals(got,want))throw new AssertionError(why+": expected "+want+", got "+got);}
 static void refuses(Runnable run,String code,String text){try{run.run();}catch(VmException e){equal(e.code,code,"refusal code");if(!e.getMessage().contains(text))throw new AssertionError("wrong refusal "+e.getMessage());return;}throw new AssertionError("missing refusal: "+text);}
 static void group(String name,String filter,Runnable test){if(name.contains(filter)){test.run();System.out.println("PASS invocation "+name);}}
 static String frame(String value){return "LMCODE_RETURN_BEGIN\n"+value+"\nLMCODE_RETURN_END\n";}
 static String output(InvocationResults r,int code){ByteArrayOutputStream out=new ByteArrayOutputStream();equal(r.writeFrame(out),code,"envelope exit");return out.toString(StandardCharsets.UTF_8);}
 static ResultContract contract(String type){return ResultContract.fromSchema("{\"dsl.schema\":2,\"result\":{\"type\":\""+type+"\"}}");}
 public static void main(String[] args){run(args.length==0?"":args[0]);}
 public static void run(String filter){
  group("sealing",filter,()->{
   InvocationResults r=InvocationResults.fromCommand(List.of("a"),null);Registry registry=new Registry();ResultSink s=r.select(0,registry,new ResultContract());s.set(Value.ofInt(7));r.complete(0);
   refuses(()->s.set(Value.ofInt(8)),"RUN_HOST","result sink is sealed");refuses(s::reset,"RUN_HOST","result sink is sealed");refuses(s::setNull,"RUN_HOST","result sink is sealed");refuses(()->s.put("x",Value.ofInt(1)),"RUN_HOST","result sink is sealed");refuses(()->r.select(0,registry,new ResultContract()),"RUN_HOST","result sink is sealed");
   equal(output(r,0),frame("{\"ok\":true,\"result\":7}"),"sealed snapshot");ByteArrayOutputStream again=new ByteArrayOutputStream();equal(r.writeFrame(again),4,"second writer refused");equal(again.size(),0,"second writer printed");
  });
  group("sink isolation and reverse order",filter,()->{
   InvocationResults r=InvocationResults.fromCommand(List.of("a","b"),null);Registry registry=new Registry();ResultSink first=r.select(0,registry,new ResultContract());first.put("counter",Value.ofInt(2));ResultSink second=r.select(1,registry,new ResultContract());
   if(first==second)throw new AssertionError("reused sink across parts");second.put("counter",Value.ofInt(1));r.select(0,registry,new ResultContract()).put("body",Value.ofBool(true));r.complete(1);r.complete(0);
   equal(output(r,0),frame("{\"ok\":true,\"result\":[{\"counter\":2,\"body\":true},{\"counter\":1}],\"parts\":2}"),"add order independent of completion");
  });
  group("first failure association",filter,()->{
   InvocationResults r=InvocationResults.fromCommand(List.of("a","b"),null);VmException e=new VmException("RUN_HOST","first");e.line=4L;e.column=2;r.fail(1,"run",e);e.line=99L;
   r.fail(999,"not-a-phase",null); // first observed failure wins before validating later arguments
   equal(output(r,3),frame("{\"ok\":false,\"phase\":\"run\",\"code\":\"RUN_HOST\",\"message\":\"first\",\"line\":4,\"column\":2,\"part\":1}"),"wrong part or lost first failure");
   InvocationResults zero=InvocationResults.fromCommand(List.of("a","b"),null);zero.fail(0,"load",new VmException("LOAD_BAD_SCHEMA","bad"));equal(output(zero,3),frame("{\"ok\":false,\"phase\":\"load\",\"code\":\"LOAD_BAD_SCHEMA\",\"message\":\"bad\",\"part\":0}"),"zero part omitted");
  });
  group("flush and single shot",filter,()->{
   class Stream extends ByteArrayOutputStream{int flushes;boolean closed;@Override public void flush(){flushes++;}@Override public void close(){closed=true;}}
   Stream out=new Stream();InvocationResults r=InvocationResults.fromCommand(List.of("a"),null);r.select(0,new Registry(),new ResultContract());r.complete(0);equal(r.writeFrame(out),0,"writer exit");equal(out.flushes,1,"flush was omitted");equal(out.closed,false,"closed caller stream");
   OutputStream broken=new OutputStream(){public void write(int b){}public void flush()throws IOException{throw new IOException("deferred");}};
   InvocationResults fail=InvocationResults.fromCommand(List.of("a"),null);fail.select(0,new Registry(),new ResultContract());fail.complete(0);equal(fail.writeFrame(broken),4,"deferred flush failure");ByteArrayOutputStream retry=new ByteArrayOutputStream();equal(fail.writeFrame(retry),4,"retry after flush");equal(retry.size(),0,"retried failed frame");
   PrintStream swallowed=new PrintStream(new OutputStream(){public void write(int b)throws IOException{throw new IOException("partial");}},true,StandardCharsets.UTF_8);
   equal(Reports.failureFrame(swallowed,"cli",new VmException("CLI_USAGE","bad")),4,"PrintStream swallowed failure");
  });
  group("contracts atomic and typed",filter,()->{
   ResultSink s=new ResultSink(contract("IntMap"));s.put("keep",Value.ofInt(1));refuses(()->s.putNull("bad/key~"),"RUN_HOST","at /bad~1key~0: expected int; got null");equal(s.json(),"{\"keep\":1}","non-atomic typed field");
   refuses(s::setNull,"RUN_HOST","expected IntMap; got null");s.reset();s.put("fresh",Value.ofInt(2));
   ResultSink list=new ResultSink(contract("FloatList"));Context c=new Context();list.set(Stdlib.makeIntList(c,List.of(3L)));equal(list.json(),"[3]","widening changed representation");
   refuses(()->new ResultSink(contract("IntList")).set(StdCollections.obj(c,"FloatList",new ArrayList<Double>())),"RUN_HOST","expected IntList; got FloatList");
   ResultSink integer=new ResultSink(contract("int"));refuses(()->integer.set(Value.ofFloat(1)),"RUN_HOST","expected int; got float");integer.set(Value.object("Json",InvocationJson.parse(InvocationJson.utf8("1.0"))));
   ResultSink json=new ResultSink(contract("Json"));refuses(json::setNull,"RUN_HOST","expected Json; got null");json.set(Value.ofString("wide"));
   for(String bad:List.of("{}","{\"type\":\"void\"}","{\"type\":\"null\",\"nullable\":false}","{\"type\":\"int\",\"nullable\":0}","{\"type\":\"int\",\"extra\":1}"))refuses(()->ResultContract.fromSchema("{\"dsl.schema\":2,\"result\":"+bad+"}"),"LOAD_BAD_SCHEMA","lmcode result contract:");
   ResultSink nullable=new ResultSink(ResultContract.fromSchema("{\"dsl.schema\":2,\"result\":{\"type\":\"int\",\"nullable\":true}}"));nullable.setNull();nullable.seal();
   ResultSink nullOnly=new ResultSink(contract("null"));nullOnly.setNull();nullOnly.seal();
  });
  group("missing completion and global failure",filter,()->{
   Registry registry=new Registry();InvocationResults missing=InvocationResults.fromCommand(List.of("a","b"),null);missing.select(0,registry,new ResultContract()).set(Value.ofInt(1));missing.complete(0);String failed=output(missing,3);if(failed.contains("\"result\"")||!failed.contains("\"part\":1")||!failed.contains("slot not completed"))throw new AssertionError("missing completion produced partial result");
   InvocationResults r=InvocationResults.fromCommand(List.of("a","b"),null);r.select(0,registry,new ResultContract());r.fail(null,"app",new VmException("APP_FAIL","global"));refuses(()->r.select(1,registry,new ResultContract()),"RUN_HOST","cannot start work after failure");r.select(0,registry,new ResultContract()).set(Value.ofInt(4));r.complete(0);equal(output(r,4),frame("{\"ok\":false,\"phase\":\"app\",\"code\":\"APP_FAIL\",\"message\":\"global\"}"),"global failure leaked payload/part");
   InvocationResults typed=InvocationResults.fromCommand(List.of("a","b"),null);typed.select(1,registry,contract("int"));refuses(()->typed.complete(1),"RUN_HOST","expected int; got null");if(!output(typed,3).contains("\"part\":1"))throw new AssertionError("completion contract failure lost part");
  });
  group("metadata and one-slot shape",filter,()->{
   String a=Path.of("part α 0").toAbsolutePath().toString(),b=Path.of("part β 1").toAbsolutePath().toString();
   String raw="{\"version\":1,\"mode\":\"builder\",\"dry\":false,\"parts\":[{\"index\":0,\"file\":"+Reports.escape(a)+"},{\"index\":1,\"file\":"+Reports.escape(b)+"}]}";
   InvocationResults r=InvocationResults.fromMetadata(raw,List.of(b,a));equal(r.registrations(),List.of(new InvocationResults.Registration(b,1),new InvocationResults.Registration(a,0)),"parser lost canonical slot indices");
   Registry reg=new Registry();r.select(0,reg,new ResultContract()).set(Value.ofFloat(.1+.2));r.complete(0);r.select(1,reg,new ResultContract());r.complete(1);equal(output(r,0),frame("{\"ok\":true,\"result\":[0.30000000000000004,null],\"parts\":2}"),"float and null aggregate");
   String one="{\"version\":1,\"mode\":\"builder\",\"dry\":false,\"parts\":[{\"index\":0,\"file\":"+Reports.escape(a)+"}]}";InvocationResults builder=InvocationResults.fromMetadata(one,List.of(a));builder.select(0,reg,new ResultContract()).set(Stdlib.makeIntList(new Context(),List.of(1L)));builder.complete(0);equal(output(builder,0),frame("{\"ok\":true,\"result\":[[1]],\"parts\":1}"),"one-slot builder flattened");
   for(String bad:List.of(raw.replace("\"version\":1","\"version\":2"),raw.replace("\"version\":1","\"version\":1,\"version\":1"),raw.replace("\"builder\"","\"single\""),raw.replace("\"index\":1","\"index\":0"),raw.replace(Reports.escape(b),Reports.escape(a)),raw.replace("\"mode\":", "\"extra\":0,\"mode\":"),raw.replace("\"index\":0","\"index\":0,\"extra\":0"),raw.replace(Reports.escape(a),"\"relative\""),raw.replace("\"version\":1","\"version\":1.5"),"\ufeff"+raw,raw+"x",raw.replace("\"parts\":[", "\"parts\":null,\"ignored\":[")))refuses(()->InvocationResults.fromMetadata(bad,List.of(a,b)),"CLI_USAGE","lmcode invocation:");
   refuses(()->InvocationResults.fromMetadata(raw,List.of(a,a)),"CLI_USAGE","duplicate registration");refuses(()->InvocationResults.fromMetadata(raw,List.of(a)),"CLI_USAGE","registration count mismatch");refuses(()->InvocationResults.fromMetadata(raw,List.of(a,"extra")),"CLI_USAGE","extra registration");
   refuses(()->InvocationResults.fromCommand(List.of(a),""),"CLI_USAGE","manifest path must be absolute");refuses(()->InvocationResults.fromCommand(List.of(a),"relative"),"CLI_USAGE","manifest path must be absolute");refuses(()->InvocationResults.fromCommand(List.of("\ud800"),null),"CLI_USAGE","file must be UTF-8");
   // Duplicate CLI occurrences have distinct slots without a descriptor.
   equal(InvocationResults.fromCommand(List.of("same","same"),null).registrations().get(1).index(),1,"standalone duplicate filename deduplicated");
  });
  group("all contract shapes and immutable bindings",filter,()->{
   Map<String,String> values=new LinkedHashMap<>();values.put("null","null");values.put("bool","true");values.put("int","2");values.put("float","2.5");values.put("string","\"water 水\"");values.put("Json","{\"nested\":[null]}");values.put("StrList","[\"a\"]");values.put("IntList","[1]");values.put("FloatList","[1,0.5]");values.put("StrMap","{\"a\":\"b\"}");values.put("IntMap","{\"a\":1}");values.put("FloatMap","{\"a\":0.5}");
   for(var e:values.entrySet()){ResultSink sink=new ResultSink(contract(e.getKey()));sink.set(Value.object("Json",InvocationJson.parse(InvocationJson.utf8(e.getValue()))));sink.seal();equal(sink.json(),e.getValue(),"contract shape "+e.getKey());}
   InvocationResults r=InvocationResults.fromCommand(List.of("a"),null);Registry registry=new Registry();r.select(0,registry,contract("IntMap")).put("kept",Value.ofInt(1));refuses(()->r.select(0,registry,contract("Json")),"RUN_HOST","result contract changed");r.complete(0);if(!output(r,0).contains("\"kept\":1"))throw new AssertionError("reselection changed contract/root");
  });
  group("global failures and location validation",filter,()->{
   ByteArrayOutputStream out=new ByteArrayOutputStream();equal(Reports.failureFrame(out,"cli",new VmException("CLI_USAGE","bad \"arg\"")),2,"global CLI exit");equal(out.toString(StandardCharsets.UTF_8),frame("{\"ok\":false,\"phase\":\"cli\",\"code\":\"CLI_USAGE\",\"message\":\"bad \\\"arg\\\"\"}"),"global failure bytes");
   InvocationResults r=InvocationResults.fromCommand(List.of("a"),null);Registry reg=new Registry();r.select(0,reg,new ResultContract());r.complete(0);VmException bad=new VmException("RUN_HOST","bad");bad.column=2;refuses(()->r.fail(0,"run",bad),"RUN_HOST","column requires a positive line");bad.column=null;bad.line=9007199254740992L;refuses(()->r.fail(0,"run",bad),"RUN_HOST","line must be positive");refuses(()->r.fail(0,"invalid",new VmException("RUN_HOST","bad")),"RUN_HOST","invalid failure phase");equal(output(r,0),frame("{\"ok\":true,\"result\":null}"),"invalid failure latched");
   refuses(()->InvocationResults.fromMetadata(new byte[]{(byte)255},List.of("a")),"CLI_USAGE","invalid UTF-8");refuses(()->InvocationResults.fromMetadata("\ud800",List.of("a")),"CLI_USAGE","utf8");refuses(()->ResultContract.fromSchema("\ud800"),"LOAD_BAD_SCHEMA","utf8");
  });
  group("dry invocation metadata and markers",filter,()->{
   String file=Path.of("dry-part.dslb").toAbsolutePath().toString();String dryMeta="{\"version\":1,\"mode\":\"single\",\"dry\":true,\"parts\":[{\"index\":0,\"file\":"+Reports.escape(file)+"}]}";
   InvocationResults success=InvocationResults.fromMetadata(dryMeta,List.of(file));if(!success.dry())throw new AssertionError("dry invocation mode was not retained");success.select(0,new Registry(),new ResultContract()).setNull();success.complete(0);equal(output(success,0),frame("{\"ok\":true,\"result\":null,\"dry\":true}"),"dry marker success order");
   InvocationResults failed=InvocationResults.fromCommand(List.of("a"),true);failed.fail(0,"run",new VmException("RUN_HOST","preview failed"));equal(output(failed,3),frame("{\"ok\":false,\"phase\":\"run\",\"code\":\"RUN_HOST\",\"message\":\"preview failed\",\"dry\":true}"),"dry marker failure order");
   for(String invalid:List.of("{\"version\":1,\"mode\":\"single\",\"parts\":[]}","{\"version\":1,\"mode\":\"single\",\"dry\":1,\"parts\":[]}"))refuses(()->InvocationResults.fromMetadata(invalid,List.of(file)),"CLI_USAGE","dry must be boolean");
   String knownInvalid="{\"version\":1,\"mode\":\"single\",\"dry\":true,\"parts\":[]}";
   try{InvocationResults.fromMetadata(knownInvalid,List.of(file));throw new AssertionError("invalid dry parts accepted");}catch(VmException error){ByteArrayOutputStream out=new ByteArrayOutputStream();Reports.failureFrame(out,"cli",error);equal(out.toString(StandardCharsets.UTF_8),frame("{\"ok\":false,\"phase\":\"cli\",\"code\":\"CLI_USAGE\",\"message\":\"lmcode invocation: invalid parts\",\"dry\":true}"),"known-mode pre-collector marker");}
  });
  DryChecks.run(filter);
 }
}
