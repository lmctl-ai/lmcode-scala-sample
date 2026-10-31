package io.lmctl.lmcode;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strict host metadata parsing; no manifest authority over schema contracts. */
final class InvocationJson {
 private InvocationJson(){}
 static StdJson.Node parse(byte[] bytes) {
  StdJson.utf8(bytes, 0);
  if(bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191)
   throw VmException.host("JSON BOM is not allowed");
  StdJson.Parser parser=new StdJson.Parser(bytes);StdJson.Node value=parser.val(0);parser.ws();
  if(parser.p!=bytes.length)throw VmException.host("trailing JSON input");
  return value;
 }
 static Object owned(byte[] bytes){return ResultJson.copyNode(parse(bytes),"",Collections.newSetFromMap(new IdentityHashMap<>()));}
 static byte[] utf8(String text){return ResultJson.key(text).bytes();}
 static StdJson.Node member(StdJson.Node node,String key){if(node.object==null)return null;for(var m:node.object)if(ResultJson.text(m.key,"").equals(key))return m.value;return null;}
 static boolean integral(Object value){return value instanceof Long || value instanceof Double d&&Double.isFinite(d)&&Math.rint(d)==d&&Math.abs(d)<=ResultJson.SAFE_INTEGER;}
 static String kind(Object value){return value==null?"null":value instanceof Boolean?"bool":value instanceof Long?"int":value instanceof Double?"float":value instanceof String?"string":value instanceof List<?>?"array":value instanceof Map<?,?>?"object":"handle";}
 static Object snapshot(Object value){
  if(value instanceof List<?> list){List<Object> copy=new ArrayList<>();for(Object item:list)copy.add(snapshot(item));return Collections.unmodifiableList(copy);}
  if(value instanceof Map<?,?> map){Map<String,Object> copy=new LinkedHashMap<>();for(var entry:map.entrySet())copy.put((String)entry.getKey(),snapshot(entry.getValue()));return Collections.unmodifiableMap(copy);}
  return value;
 }
}
