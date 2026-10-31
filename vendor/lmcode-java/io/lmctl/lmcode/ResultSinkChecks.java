package io.lmctl.lmcode;

import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;

/** Native-reachable semantic checks; production-copy mutations must trip these assertions. */
public final class ResultSinkChecks {
 private ResultSinkChecks(){}
 static Value call(Registry r,Context c,String signature,Value... args){
  Registry.Binding binding=r.resolve(signature);if(binding==null)throw new AssertionError("missing "+signature);
  return binding.callback.call(c,Value.none(),args);
 }
 static void equal(Object actual,Object expected,String why){if(!Objects.equals(actual,expected))throw new AssertionError(why+": expected "+expected+", got "+actual);}
 static void refuses(Runnable run,String message){try{run.run();}catch(VmException e){equal(e.code,"RUN_HOST","conversion code");equal(e.getMessage(),message,"conversion diagnostic");return;}throw new AssertionError("expected refusal: "+message);}
 static Value parsed(Registry r,Context c,String source){return call(r,c,"fn json_parse(string) -> Json",Value.ofString(source));}
 static void group(String name,Runnable run,String filter){if(!name.contains(filter))return;run.run();System.out.println("PASS "+name);}
 public static void main(String[] args){run(args.length==0?"":args[0]);}
 public static void run(String filter){
  group("registration and missing sink",()->{
   Registry r=new Registry();equal(r.stdSignatures().stream().filter(s->s.startsWith("fn lmcode_return_")).count(),24L,"24 overloads");
   refuses(()->call(r,new Context(),"fn lmcode_return_set(int) -> void",Value.ofInt(1)),"lmcode return: no result sink");
   equal(new Registry(false).stdSignatures().size(),0,"explicit empty registry");
  },filter);
  group("null framing and replacement order",()->{
   ResultSink s=new ResultSink();equal(new String(Reports.resultFrame(s),java.nio.charset.StandardCharsets.UTF_8),"LMCODE_RETURN_BEGIN\nnull\nLMCODE_RETURN_END\n","mandatory null frame");
   s.put("a",Value.ofInt(1));s.put("b",Value.ofBool(false));s.put("a",Value.ofString("last"));s.putNull("nil");
   equal(s.json(),"{\"a\":\"last\",\"b\":false,\"nil\":null}","ordered overwrite");s.setNull();s.put("",Value.ofInt(2));equal(s.json(),"{\"\":2}","empty key");s.reset();equal(s.json(),"null","new invocation reset");
  },filter);
  group("atomic conversion and field shape",()->{
   ResultSink s=new ResultSink();s.put("keep",Value.ofInt(3));
   refuses(()->s.set(Value.ofFloat(Double.POSITIVE_INFINITY)),"lmcode return: invalid JSON value at : nonfinite");
   refuses(()->s.put("bad/key~",Value.ofInt(9007199254740992L)),"lmcode return: invalid JSON value at /bad~1key~0: unsafe_integer");
   equal(s.json(),"{\"keep\":3}","failed write mutated prior root");s.set(Value.ofInt(5));
   refuses(()->s.put("x",Value.ofInt(1)),"lmcode return: field assignment requires object or null");equal(s.json(),"5","field shape changed root");
  },filter);
  group("owned list survives mutation and arena exit",()->{
   Registry r=new Registry();ResultSink s=new ResultSink();r.setResultSink(s);Context c=new Context();r.enterRun(c);
   try{
    Value list=Stdlib.makeIntList(c,List.of(7L,8L));call(r,c,"fn lmcode_return_put(string, IntList) -> void",Value.ofString("saved"),list);
    r.resolve("method IntList.clear() -> IntList").callback.call(c,list,new Value[0]);
   }finally{r.exitRun(c);}
   equal(s.json(),"{\"saved\":[7,8]}","arena-alias/source-mutation");
   r.enterRun(c);try{call(r,c,"fn lmcode_return_put(string, bool) -> void",Value.ofString("entry"),Value.ofBool(true));}finally{r.exitRun(c);}
   equal(s.json(),"{\"saved\":[7,8],\"entry\":true}","reset-between-calls");
  },filter);
  group("owned typed maps and lists",()->{
   Context c=new Context();ResultSink s=new ResultSink();
   var strings=new ArrayList<>(List.of(VmString.fromString("one")));
   var floats=new ArrayList<>(List.of(.5,-0.0));var sm=new LinkedHashMap<VmString,VmString>();sm.put(VmString.fromString("s"),VmString.fromString("v"));
   var im=new LinkedHashMap<VmString,Long>();im.put(VmString.fromString("i"),8L);var fm=new LinkedHashMap<VmString,Double>();fm.put(VmString.fromString("f"),.25);
   s.put("a",StdCollections.obj(c,"StrList",strings));s.put("b",StdCollections.obj(c,"FloatList",floats));s.put("c",StdCollections.obj(c,"StrMap",sm));s.put("d",StdCollections.obj(c,"IntMap",im));s.put("e",StdCollections.obj(c,"FloatMap",fm));
   strings.clear();floats.clear();sm.clear();im.clear();fm.clear();
   equal(s.json(),"{\"a\":[\"one\"],\"b\":[0.5,0],\"c\":{\"s\":\"v\"},\"d\":{\"i\":8},\"e\":{\"f\":0.25}}","map/list source alias");
  },filter);
  group("owned Json graph and borrowed child",()->{
   Registry r=new Registry();Context c=new Context();ResultSink s=new ResultSink();r.enterRun(c);
   try{
    Value json=parsed(r,c,"{\"x\":[1,true,null],\"message\":\"yes\"}");s.set(json);
    StdJson.Node n=(StdJson.Node)StdCollections.unwrap(json);n.object.getFirst().value.array.clear();
   }finally{r.exitRun(c);}
   equal(s.json(),"{\"x\":[1,true,null],\"message\":\"yes\"}","deep Json copy");
   r.enterRun(c);try{
    Value parent=parsed(r,c,"{\"child\":[1,true,null]}");
    Value child=r.resolve("method Json.get(string) -> Json").callback.call(c,parent,new Value[]{Value.ofString("child")});
    s.set(child);((StdJson.Node)StdCollections.unwrap(parent)).object.getFirst().value.array.clear();
   }finally{r.exitRun(c);}
   equal(s.json(),"[1,true,null]","borrowed Json child not owned");
  },filter);
  group("shortest float and zero",()->{
   ResultSink s=new ResultSink();s.set(Value.ofFloat(.1+.2));
   equal(new String(Reports.resultFrame(s),java.nio.charset.StandardCharsets.UTF_8),"LMCODE_RETURN_BEGIN\n0.30000000000000004\nLMCODE_RETURN_END\n","shortest float frame, not str(float)");
   s.set(Value.ofFloat(.1));equal(s.json(),"0.1","shortest 0.1, not 17-digit expansion");
   s.set(Value.ofFloat(-0.0));equal(s.json(),"0","negative zero");s.set(Value.ofFloat(Double.MIN_VALUE));equal(Double.parseDouble(s.json()),Double.MIN_VALUE,"subnormal round trip");
   for(double value:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY})refuses(()->s.set(Value.ofFloat(value)),"lmcode return: invalid JSON value at : nonfinite");
   refuses(()->s.set(Value.ofFloat(9007199254740992.0)),"lmcode return: invalid JSON value at : unsafe_integer");
  },filter);
  group("raw numeric tokens and underflow",()->{
   ResultSink s=new ResultSink();StdJson.Node node=new StdJson.Node();node.kind=StdJson.Kind.INT;node.integerValue=1;node.raw=VmString.fromString("9007199254740993");
   refuses(()->s.set(Value.object("Json",node)),"lmcode return: invalid JSON value at : unsafe_integer");
   node.kind=StdJson.Kind.FLOAT;node.floatValue=1;node.raw=VmString.fromString("1e20");refuses(()->s.set(Value.object("Json",node)),"lmcode return: invalid JSON value at : unsafe_integer");
   node.floatValue=0;node.raw=VmString.fromString("1e-999");refuses(()->s.set(Value.object("Json",node)),"lmcode return: invalid JSON value at : underflow");
   node.raw=VmString.fromString("0e-999");s.set(Value.object("Json",node));equal(s.json(),"0","zero exponent is not underflow");
   Registry r=new Registry();Context c=new Context();
   Value bad=parsed(r,c,"{\"a/b~c\":[9007199254740993]}");refuses(()->s.set(bad),"lmcode return: invalid JSON value at /a~1b~0c/0: unsafe_integer");
   for(String source:List.of("9007199254740991","9007199254740991.0","90071992547409910e-1","5e-324","-0.0"))s.set(parsed(r,c,source));
   for(String source:List.of("9007199254740992","9007199254740993","9.007199254740992e15","9007199254740991.9"))refuses(()->s.set(parsed(r,c,source)),"lmcode return: invalid JSON value at : unsafe_integer");
  },filter);
  group("decimal lexical classification",()->{
   for(String token:List.of("9007199254740991","-9007199254740991","9007199254740991.0","90071992547409910e-1","9.007199254740991e15","1.0000000000000000","0e-999999999999999999999999999","-0.0e99999999999999999999999999","1e-20","0.00001e20"))equal(ResultJson.decimalUnsafeInteger(token,""),false,"safe/fractional raw token "+token);
   for(String token:List.of("9007199254740992","9007199254740993","-9007199254740993","90071992547409930000e-4","9.007199254740992e15","1e20","1e99999999999999999999999999"))equal(ResultJson.decimalUnsafeInteger(token,""),true,"unsafe raw integer "+token);
  },filter);
  group("UTF8 values keys and arbitrary handles",()->{
   ResultSink s=new ResultSink();s.set(Value.ofString("kept"));VmString invalid=new VmString(new byte[]{(byte)255});
   refuses(()->s.set(Value.ofString(invalid)),"lmcode return: invalid JSON value at : utf8");equal(s.json(),"\"kept\"","invalid text changed sink");s.setNull();
   refuses(()->s.put(invalid,Value.ofInt(1)),"lmcode return: invalid JSON value at : utf8");equal(s.json(),"null","invalid key promoted root");
   refuses(()->s.set(Value.object("Widget",new Object())),"lmcode return: invalid JSON value at : handle");
   refuses(()->s.set(Value.object("Json",null)),"lmcode return: invalid JSON value at : handle");
  },filter);
  group("cycles and duplicate JSON keys",()->{
   ResultSink s=new ResultSink();StdJson.Node node=new StdJson.Node();node.kind=StdJson.Kind.OBJECT;node.object=new ArrayList<>();node.object.add(new StdJson.Member(VmString.fromString("self"),null,node));
   refuses(()->s.set(Value.object("Json",node)),"lmcode return: invalid JSON value at /self: cycle");node.object.clear();
   StdJson.Node nil=new StdJson.Node();node.object.add(new StdJson.Member(VmString.fromString("x"),null,nil));node.object.add(new StdJson.Member(VmString.fromString("x"),null,nil));
   refuses(()->s.set(Value.object("Json",node)),"lmcode return: invalid JSON value at /x: handle");
   try{parsed(new Registry(),new Context(),"{\"x\":1,\"x\":2}");throw new AssertionError("duplicate parser keys merged");}catch(VmException expected){if(!expected.getMessage().contains("duplicate object key"))throw expected;}
  },filter);
  group("library silence and marker escaping",()->{
   var oldOut=System.out;var oldErr=System.err;
   ByteArrayOutputStream out=new ByteArrayOutputStream(),err=new ByteArrayOutputStream();
   ResultSink sink=new ResultSink();Registry registry=new Registry().setResultSink(sink);Context context=new Context();
   String marker="LMCODE_RETURN_BEGIN\nLMCODE_RETURN_END";
   try{
    System.setOut(new java.io.PrintStream(out,true,java.nio.charset.StandardCharsets.UTF_8));
    System.setErr(new java.io.PrintStream(err,true,java.nio.charset.StandardCharsets.UTF_8));
    call(registry,context,"fn lmcode_return_set(string) -> void",Value.ofString(marker));
    equal(new String(Reports.resultFrame(sink),java.nio.charset.StandardCharsets.UTF_8).split("\n",-1).length,4,"escaped marker changed frame line count");
    call(registry,context,"fn lmcode_return_null() -> void");
    call(registry,context,"fn lmcode_return_put(string, bool) -> void",Value.ofString("b"),Value.ofBool(true));
    call(registry,context,"fn lmcode_return_put_null(string) -> void",Value.ofString("n"));
   }finally{System.setOut(oldOut);System.setErr(oldErr);}
   equal(out.size(),0,"native library printed stdout");equal(err.size(),0,"native library printed stderr");
  },filter);
  group("deferred stream failure",()->{
   boolean[] closed={false};
   java.io.OutputStream output=new java.io.OutputStream(){
    public void write(int value){}
    public void flush()throws java.io.IOException{throw new java.io.IOException("deferred frame failure");}
    public void close(){closed[0]=true;}
   };
   try{Reports.writeResultFrame(output,new ResultSink());throw new AssertionError("frame writer swallowed deferred stream failure");}
   catch(java.io.IOException expected){equal(expected.getMessage(),"deferred frame failure","stream error preserved");}
   equal(closed[0],false,"frame helper closed caller stream");
  },filter);
  group("PrintStream write failure",()->{
   java.io.PrintStream output=new java.io.PrintStream(new java.io.OutputStream(){public void write(int value)throws java.io.IOException{throw new java.io.IOException("broken stream");}},true,java.nio.charset.StandardCharsets.UTF_8);
   try{Reports.writeResultFrame(output,new ResultSink());throw new AssertionError("frame writer ignored PrintStream error");}
   catch(java.io.IOException expected){equal(expected.getMessage(),"lmcode return: cannot write result frame","PrintStream error surfaced");}
  },filter);
  group("concurrent invocations and LF output",()->{
   ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
   try{
    List<Future<String>> futures=new ArrayList<>();
    for(String value:List.of("a","b"))futures.add(pool.submit(()->{Registry r=new Registry();Context c=new Context();ResultSink s=new ResultSink();r.setResultSink(s);r.enterRun(c);try{ready.countDown();go.await();call(r,c,"fn lmcode_return_set(string) -> void",Value.ofString(value));}finally{r.exitRun(c);}return s.json();}));
    if(!ready.await(10,TimeUnit.SECONDS))throw new AssertionError("concurrent invocation did not enter");go.countDown();
    equal(futures.get(0).get(10,TimeUnit.SECONDS),"\"a\"","first sink isolation");equal(futures.get(1).get(10,TimeUnit.SECONDS),"\"b\"","second sink isolation");
    ResultSink s=new ResultSink();ByteArrayOutputStream out=new ByteArrayOutputStream();Reports.writeResultFrame(out,s);
    equal(out.toString(java.nio.charset.StandardCharsets.UTF_8),"LMCODE_RETURN_BEGIN\nnull\nLMCODE_RETURN_END\n","LF output stream");
   }catch(Exception error){throw new AssertionError(error);}finally{go.countDown();pool.shutdownNow();}
  },filter);
 }
}
