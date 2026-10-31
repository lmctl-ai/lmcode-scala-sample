package io.lmctl.lmcode;

import java.io.OutputStream;

/** The byte oriented, dependency free lmcode standard catalog. */
public final class Stdlib {
  private Stdlib() {}
  static Registry safeFunction(Registry r,String name,java.util.List<String> params,String returns,Registry.Callback callback){return r.function(name,params,returns,callback,Registry.dry(callback));}
  static Registry safeMethod(Registry r,String type,String name,java.util.List<String> params,String returns,Registry.Callback callback){return r.method(type,name,params,returns,callback,Registry.dry(callback));}
  static Registry dryRealFunction(Registry r,String name,java.util.List<String> params,String returns,Registry.Callback callback){return r.function(name,params,returns,callback,Registry.dry(callback));}
  public static void register(Registry r) {
    r.beginBuiltinRegistration();
    try { StdString.register(r); StdMath.register(r); StdCollections.register(r, true); StdContainers.register(r); StdJson.register(r); StdFile.register(r); StdReturn.register(r); }
    finally { r.endBuiltinRegistration(); }
  }
  public static void registerTesting(Registry r, OutputStream out) {
    StdTesting.register(r, out);
  }
  static void beginRun(Context c) { StdCollections.beginRun(c); }
  static void endRun(Context c) { StdCollections.endRun(c); }
  static Value dryDummy(Context c,String declared){
    return switch(declared){
      case "void"->Value.none();
      case "null"->Value.dryNull(declared);
      case "bool"->Value.ofBool(true);
      case "int","integer","int64"->Value.ofInt(0);
      case "float","double","number"->Value.ofFloat(0);
      case "string"->Value.ofString("");
      case "StrList"->StdCollections.obj(c,"StrList",new java.util.ArrayList<VmString>());
      case "IntList"->StdCollections.obj(c,"IntList",new java.util.ArrayList<Long>());
      case "FloatList"->StdCollections.obj(c,"FloatList",new java.util.ArrayList<Double>());
      case "StrMap"->StdCollections.obj(c,"StrMap",new java.util.LinkedHashMap<VmString,VmString>());
      case "IntMap"->StdCollections.obj(c,"IntMap",new java.util.LinkedHashMap<VmString,Long>());
      case "FloatMap"->StdCollections.obj(c,"FloatMap",new java.util.LinkedHashMap<VmString,Double>());
      default->Value.dryNull(declared);
    };
  }
  public static Value makeStrList(Context c, java.util.Collection<VmString> items) {
    return StdCollections.obj(c, "StrList", new java.util.ArrayList<>(items));
  }
  public static Value makeIntList(Context c, java.util.Collection<Long> items) {
    return StdCollections.obj(c, "IntList", new java.util.ArrayList<>(items));
  }
  public static Value makeFloatList(Context c, java.util.Collection<Double> items) {
    return StdCollections.obj(c, "FloatList", new java.util.ArrayList<>(items));
  }
  public static Value makeStrMap(Context c, java.util.Map<VmString, VmString> entries) {
    return StdCollections.obj(c, "StrMap", new java.util.LinkedHashMap<>(entries));
  }
  /** Read-only host snapshot; valid while its originating collection arena is live. */
  public static java.util.List<Value> collectionValues(Value collection) {
    Object value=StdCollections.unwrap(collection);
    if(!(value instanceof java.util.List<?> list))throw VmException.host("expected a standard list");
    java.util.ArrayList<Value> out=new java.util.ArrayList<>();
    for(Object element:list)out.add(switch(collection.objectType()){case "StrList"->Value.ofString((VmString)element);case "IntList"->Value.ofInt((Long)element);case "FloatList"->Value.ofFloat((Double)element);default->throw VmException.host("expected a standard list");});
    return java.util.List.copyOf(out);
  }
  public static java.util.Map<VmString,VmString> mapEntries(Value collection) {
    Object value=StdCollections.unwrap(collection);
    if(!(value instanceof java.util.Map<?,?> map))throw VmException.host("expected a standard map");
    java.util.LinkedHashMap<VmString,VmString> out=new java.util.LinkedHashMap<>();
    map.forEach((k,v)->out.put((VmString)k,(VmString)v));return java.util.Collections.unmodifiableMap(out);
  }
}
