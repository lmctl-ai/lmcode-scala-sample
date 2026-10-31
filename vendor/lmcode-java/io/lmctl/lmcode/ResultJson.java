package io.lmctl.lmcode;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The result boundary never serializes retained Json raw text or str(float). */
final class ResultJson {
  static final long SAFE_INTEGER = 9007199254740991L;
  private ResultJson() {}
  static VmException invalid(String path, String reason) {
    return VmException.host("lmcode return: invalid JSON value at " + path + ": " + reason);
  }
  static String pointer(String parent, String key) { return parent + "/" + key.replace("~", "~0").replace("/", "~1"); }
  static String text(VmString value, String path) {
    if (value == null) throw invalid(path,"handle");
    try { return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value.raw())).toString(); }
    catch (CharacterCodingException error) { throw invalid(path,"utf8"); }
  }
  static VmString key(String value) {
    if(value==null)throw invalid("","utf8");
    try {
      ByteBuffer bytes=StandardCharsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value));
      byte[] owned=new byte[bytes.remaining()];bytes.get(owned);return new VmString(owned);
    } catch(CharacterCodingException error){throw invalid("","utf8");}
  }
  static long integer(long value, String path) {
    if(value>SAFE_INTEGER || value < -SAFE_INTEGER)throw invalid(path,"unsafe_integer");
    return value;
  }
  static double number(double value,String path) {
    if(!Double.isFinite(value))throw invalid(path,"nonfinite");
    if(value!=0 && Math.rint(value)==value && Math.abs(value)>SAFE_INTEGER)throw invalid(path,"unsafe_integer");
    return value==0 ? 0 : value;
  }
  // Bound exponent accumulation by token length. No exponent-sized BigDecimal or
  // string allocation; this also handles huge exponents on exact zero tokens.
  static boolean decimalUnsafeInteger(String raw,String path) {
    if(raw.isEmpty())return false;
    int at=0,fractional=0;StringBuilder digits=new StringBuilder();
    if(raw.charAt(at)=='-')at++;
    if(at==raw.length())throw invalid(path,"handle");
    if(raw.charAt(at)=='0')digits.append(raw.charAt(at++));
    else {
      if(raw.charAt(at)<'1'||raw.charAt(at)>'9')throw invalid(path,"handle");
      while(at<raw.length()&&digit(raw.charAt(at)))digits.append(raw.charAt(at++));
    }
    if(at<raw.length()&&raw.charAt(at)=='.'){
      int first=++at;
      while(at<raw.length()&&digit(raw.charAt(at))){digits.append(raw.charAt(at++));fractional++;}
      if(at==first)throw invalid(path,"handle");
    }
    boolean negative=false,huge=false;long exponent=0;
    if(at<raw.length()&&(raw.charAt(at)=='e'||raw.charAt(at)=='E')){
      at++;if(at<raw.length()&&(raw.charAt(at)=='+'||raw.charAt(at)=='-'))negative=raw.charAt(at++)=='-';
      int first=at;long bound=(long)raw.length()+16;
      while(at<raw.length()&&digit(raw.charAt(at))){
        if(!huge){int d=raw.charAt(at)-'0';if(exponent>bound/10 || exponent==bound/10&&d>bound%10)huge=true;else exponent=exponent*10+d;}at++;
      }
      if(at==first)throw invalid(path,"handle");
    }
    if(at!=raw.length())throw invalid(path,"handle");
    int first=0;while(first<digits.length()&&digits.charAt(first)=='0')first++;
    if(first==digits.length())return false;
    String value=digits.substring(first);
    if(huge)return !negative;
    long denominator=0,zeros=0;
    if(negative){if(exponent>value.length() || fractional>value.length()-exponent)return false;denominator=fractional+exponent;}
    else if(exponent>=fractional)zeros=exponent-fractional;else denominator=fractional-exponent;
    if(denominator>0){
      if(denominator>=value.length())return false;
      for(int i=0;i<denominator;i++)if(value.charAt(value.length()-1-i)!='0')return false;
      value=value.substring(0,value.length()-(int)denominator);
    }
    String safe=Long.toString(SAFE_INTEGER);
    if(value.length()>safe.length() || zeros>safe.length()-value.length())return true;
    value += "0".repeat((int)zeros);
    return value.length()==safe.length()&&value.compareTo(safe)>0;
  }
  private static boolean digit(char c){return c>='0'&&c<='9';}
  private static void rawNumber(StdJson.Node node,String path){
    String raw=node.raw==null?"":new String(node.raw.raw(),StandardCharsets.ISO_8859_1);
    if(node.kind==StdJson.Kind.INT){
      if(decimalUnsafeInteger(raw,path))throw invalid(path,"unsafe_integer");
      integer(node.integerValue,path);return;
    }
    number(node.floatValue,path);
    boolean nonzero=false;
    for(int i=0;i<raw.length()&&raw.charAt(i)!='e'&&raw.charAt(i)!='E';i++)if(raw.charAt(i)>='1'&&raw.charAt(i)<='9'){nonzero=true;break;}
    if(nonzero&&node.floatValue==0)throw invalid(path,"underflow");
    if(decimalUnsafeInteger(raw,path))throw invalid(path,"unsafe_integer");
  }
  static Object copy(Value value,String path){
    if(value==null)throw invalid(path,"handle");
    return switch(value.kind){
      case NONE -> null;
      case INT -> integer(value.asInt(),path);
      case FLOAT -> number(value.asFloat(),path);
      case BOOL -> value.asBool();
      case STR -> text(value.asString(),path);
      case OBJ -> copyHandle(value,path);
    };
  }
  private static Object copyHandle(Value value,String path){
    if(value.asObject()==null)throw invalid(path,"handle");
    Object raw;
    try {raw=StdCollections.unwrap(value);}catch(VmException error){throw invalid(path,"handle");}
    String type=value.objectType();
    if(type.equals("Json")){
      if(!(raw instanceof StdJson.Node node))throw invalid(path,"handle");
      return copyNode(node,path,Collections.newSetFromMap(new IdentityHashMap<>()));
    }
    if(type.equals("StrList")||type.equals("IntList")||type.equals("FloatList"))return copyList(type,raw,path);
    if(type.equals("StrMap")||type.equals("IntMap")||type.equals("FloatMap")){
      if(!(raw instanceof Map<?,?> map))throw invalid(path,"handle");
      Map<String,Object> out=new LinkedHashMap<>();
      for(var entry:map.entrySet()){
        if(!(entry.getKey() instanceof VmString key))throw invalid(path,"handle");
        String name=text(key,path),child=pointer(path,name);
        if(out.containsKey(name))throw invalid(child,"handle");
        out.put(name,copyElement(type,entry.getValue(),child));
      }
      return out;
    }
    throw invalid(path,"handle");
  }
  private static List<Object> copyList(String type,Object raw,String path){
    if(!(raw instanceof List<?> list))throw invalid(path,"handle");
    List<Object> out=new ArrayList<>();
    for(int i=0;i<list.size();i++)out.add(copyElement(type,list.get(i),path+"/"+i));
    return out;
  }
  private static Object copyElement(String type,Object item,String path){
    if(type.startsWith("Str")&&item instanceof VmString s)return text(s,path);
    if(type.startsWith("Int")&&item instanceof Long n)return integer(n,path);
    if(type.startsWith("Float")&&item instanceof Double d)return number(d,path);
    throw invalid(path,"handle");
  }
  private static Object copyNode(StdJson.Node node,String path,Set<Object> active){
    if(node==null||node.kind==null)throw invalid(path,"handle");
    if(!active.add(node))throw invalid(path,"cycle");
    try {
      return switch(node.kind){
        case NULL -> null;case BOOL -> node.booleanValue;
        case INT -> {rawNumber(node,path);yield node.integerValue;}
        case FLOAT -> {rawNumber(node,path);yield number(node.floatValue,path);}
        case STRING -> text(node.text,path);
        case ARRAY -> {
          if(node.array==null)throw invalid(path,"handle");
          List<Object> out=new ArrayList<>();
          for(int i=0;i<node.array.size();i++)out.add(copyNode(node.array.get(i),path+"/"+i,active));
          yield out;
        }
        case OBJECT -> {
          if(node.object==null)throw invalid(path,"handle");
          Map<String,Object> out=new LinkedHashMap<>();
          for(var member:node.object){
            if(member==null)throw invalid(path,"handle");
            String name=text(member.key,path),child=pointer(path,name);
            if(out.containsKey(name))throw invalid(child,"handle");
            out.put(name,copyNode(member.value,child,active));
          }
          yield out;
        }
      };
    }finally{active.remove(node);}
  }
  static String encode(Object value){StringBuilder out=new StringBuilder();emit(value,out);return out.toString();}
  private static void emit(Object value,StringBuilder out){
    if(value==null){out.append("null");return;}
    if(value instanceof String text){out.append(Reports.escape(text));return;}
    if(value instanceof Long || value instanceof Boolean){out.append(value);return;}
    if(value instanceof Double number){
      double v=number;
      if(v==0){out.append('0');return;}
      // Consensus formatter: Java Double.toString, NEVER Formatting.formatG15.
      String text=Double.toString(v);
      if(text.endsWith(".0"))text=text.substring(0,text.length()-2);
      out.append(text);return;
    }
    if(value instanceof List<?> list){out.append('[');boolean first=true;for(Object item:list){if(!first)out.append(',');first=false;emit(item,out);}out.append(']');return;}
    if(value instanceof Map<?,?> map){out.append('{');boolean first=true;for(var entry:map.entrySet()){if(!first)out.append(',');first=false;out.append(Reports.escape((String)entry.getKey())).append(':');emit(entry.getValue(),out);}out.append('}');return;}
    throw invalid("","handle");
  }
}
