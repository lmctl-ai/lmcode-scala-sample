package io.lmctl.lmcode;
import java.util.List;
/** Imports bind a host-owned sink on this registry, not an arena/global default. */
final class StdReturn {
 private StdReturn(){}
 static void register(Registry registry){
  for(String type:List.of("int","float","bool","string","Json","StrList","IntList","FloatList","StrMap","IntMap","FloatMap")){
   registry.function("lmcode_return_set",List.of(type),"void",(c,s,a)->{registry.resultSink().set(a[0]);return Value.none();});
   registry.function("lmcode_return_put",List.of("string",type),"void",(c,s,a)->{registry.resultSink().put(a[0].asString(),a[1]);return Value.none();});
  }
  registry.function("lmcode_return_null",List.of(),"void",(c,s,a)->{registry.resultSink().setNull();return Value.none();});
  registry.function("lmcode_return_put_null",List.of("string"),"void",(c,s,a)->{registry.resultSink().putNull(a[0].asString());return Value.none();});
 }
}
