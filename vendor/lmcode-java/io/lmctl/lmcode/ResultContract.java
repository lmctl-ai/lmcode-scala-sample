package io.lmctl.lmcode;

import java.util.*;

/** Optional per-program result declaration from authoritative schema bytes. */
public record ResultContract(String type, boolean nullable) {
 private static final Set<String> TYPES=Set.of("null","bool","int","float","string","Json","StrList","IntList","FloatList","StrMap","IntMap","FloatMap");
 public ResultContract(){this("",false);}
 public ResultContract {
  if(type==null||(!type.isEmpty()&&!TYPES.contains(type))||(type.equals("null")&&nullable)||(type.isEmpty()&&nullable))
   throw new IllegalArgumentException("invalid result contract");
 }
 public static ResultContract fromSchema(String schema){try{return fromSchema(InvocationJson.utf8(schema));}catch(VmException e){if(e.code.equals("LOAD_BAD_SCHEMA"))throw e;throw new VmException("LOAD_BAD_SCHEMA","lmcode result contract: "+e.getMessage());}}
 public static ResultContract fromSchema(byte[] schema){
  try{
   StdJson.Node root=InvocationJson.parse(schema);
   if(root.kind!=StdJson.Kind.OBJECT)throw VmException.host("schema must be an object");
   StdJson.Node version=InvocationJson.member(root,"dsl.schema");
   if(version==null||!InvocationJson.integral(ResultJson.copyNode(version,"",Collections.newSetFromMap(new IdentityHashMap<>())))||
      (version.kind==StdJson.Kind.INT?version.integerValue:version.floatValue)!=2)throw VmException.host("requires schema 2");
   StdJson.Node result=InvocationJson.member(root,"result");if(result==null)return new ResultContract();
   if(result.kind!=StdJson.Kind.OBJECT)throw VmException.host("result must be an object");
   for(var m:result.object){String key=ResultJson.text(m.key,"");if(!key.equals("type")&&!key.equals("nullable"))throw VmException.host("unknown key "+key);}
   StdJson.Node t=InvocationJson.member(result,"type"),n=InvocationJson.member(result,"nullable");
   if(t==null||t.kind!=StdJson.Kind.STRING)throw VmException.host("type must be a string");
   String type=ResultJson.text(t.text,"");if(!TYPES.contains(type))throw VmException.host("unknown type "+type);
   if(n!=null&&type.equals("null"))throw VmException.host("type null forbids nullable");
   if(n!=null&&n.kind!=StdJson.Kind.BOOL)throw VmException.host("nullable must be bool");
   return new ResultContract(type,n!=null&&n.booleanValue);
  }catch(VmException|IllegalArgumentException e){throw new VmException("LOAD_BAD_SCHEMA","lmcode result contract: "+e.getMessage());}
 }
 static void mismatch(String path,String expected,String got){throw VmException.host("lmcode return: result type mismatch at "+path+": expected "+expected+"; got "+got);}
 private static boolean primitive(Object value,String type){return switch(type){case "int"->InvocationJson.integral(value);case "float"->value instanceof Long||value instanceof Double;case "bool"->value instanceof Boolean;case "string"->value instanceof String;default->false;};}
 void validate(Object value){validate(value,"");}
 private void validate(Object value,String path){
  if(type.isEmpty())return;
  if(value==null){if(nullable||type.equals("null"))return;mismatch(path,type,"null");}
  if(type.equals("Json")||primitive(value,type))return;
  boolean list=type.endsWith("List"),map=type.endsWith("Map");String element=type.startsWith("Str")?"string":type.startsWith("Int")?"int":"float";
  if(list&&value instanceof List<?> values){for(int i=0;i<values.size();i++)if(!primitive(values.get(i),element))mismatch(path+"/"+i,element,InvocationJson.kind(values.get(i)));return;}
  if(map&&value instanceof Map<?,?> values){for(var e:values.entrySet())if(!primitive(e.getValue(),element))mismatch(ResultJson.pointer(path,(String)e.getKey()),element,InvocationJson.kind(e.getValue()));return;}
  mismatch(path,type,InvocationJson.kind(value));
 }
 static String sourceType(Value value){return switch(value.kind){case NONE->"null";case INT->"int";case FLOAT->"float";case BOOL->"bool";case STR->"string";case OBJ->value.objectType();};}
 static boolean assignable(String actual,String expected){return actual.equals(expected)||actual.equals("int")&&expected.equals("float")||actual.equals("IntList")&&expected.equals("FloatList")||actual.equals("IntMap")&&expected.equals("FloatMap");}
 void validateSource(Value value){if(type.isEmpty()||type.equals("Json")||value.kind==Value.Kind.NONE||value.kind==Value.Kind.OBJ&&value.objectType().equals("Json"))return;String actual=sourceType(value);if(!assignable(actual,type))mismatch("",type,actual);}
 void validateFieldSource(String key,Value value){
  if(type.isEmpty()||type.equals("Json")||value.kind==Value.Kind.OBJ&&value.objectType().equals("Json"))return;
  String expected=switch(type){case "IntMap"->"int";case "FloatMap"->"float";case "StrMap"->"string";default->"";};
  if(!expected.isEmpty()&&!assignable(sourceType(value),expected))mismatch(ResultJson.pointer("",key),expected,sourceType(value));
 }
}
