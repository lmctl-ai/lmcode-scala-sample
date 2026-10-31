package io.lmctl.lmcode;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
public final class Reports {
 private Reports(){}
 static byte[] framed(String json){return ("LMCODE_RETURN_BEGIN\n"+json+"\nLMCODE_RETURN_END\n").getBytes(StandardCharsets.UTF_8);}
 static int exitCode(String phase){return switch(phase){case "cli"->2;case "load","run"->3;case "app"->4;default->throw VmException.host("lmcode invocation: invalid failure phase");};}
 static String failureJson(String phase,VmException e,Integer part){
  exitCode(phase);InvocationJson.utf8(e.code);InvocationJson.utf8(e.getMessage());
  String out="{\"ok\":false,\"phase\":"+escape(phase)+",\"code\":"+escape(e.code)+",\"message\":"+escape(e.getMessage());
  if(e.column!=null&&(e.line==null||e.line<=0||e.column<=0))throw VmException.host("lmcode invocation: column requires a positive line");
  if(e.line!=null){if(e.line<=0||e.line>ResultJson.SAFE_INTEGER)throw VmException.host("lmcode invocation: line must be positive");out+=",\"line\":"+e.line;if(e.column!=null)out+=",\"column\":"+e.column;}
  if(part!=null)out+=",\"part\":"+part;return out+"}";
 }
 static void outputError(){try{System.err.print("lmcode invocation: cannot write envelope\n");}catch(RuntimeException ignored){}}
 static int emit(java.io.OutputStream output,String json,int code){
  try{output.write(framed(json));if(output instanceof java.io.PrintStream stream){if(stream.checkError())throw new java.io.IOException("output failed");}else output.flush();return code;}
  catch(java.io.IOException|RuntimeException e){outputError();return 4;}
 }
 /** Only for controlled errors before collector construction; hosts return immediately. */
 public static int failureFrame(java.io.OutputStream output,String phase,VmException error){return emit(output,failureJson(phase,error,null),exitCode(phase));}
 public static int failureFrame(String phase,VmException error){return failureFrame(System.out,phase,error);}

 public static byte[] escapeBytes(VmString s){return escapeBytes(s.bytes());}
 public static byte[] escapeBytes(byte[] raw){ByteArrayOutputStream b=new ByteArrayOutputStream(raw.length+2);b.write('"');for(byte v:raw){int c=v&255;switch(c){case '"'->{b.write('\\');b.write('"');}case '\\'->{b.write('\\');b.write('\\');}case '\n'->{b.write('\\');b.write('n');}case '\r'->{b.write('\\');b.write('r');}case '\t'->{b.write('\\');b.write('t');}default->{if(c<32)b.writeBytes(String.format(java.util.Locale.ROOT,"\\u%04x",c).getBytes(StandardCharsets.US_ASCII));else b.write(c);}}}b.write('"');return b.toByteArray();}
 public static String escape(VmString s){return escape(s.toString());}
 public static String escape(String s){StringBuilder b=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\b'->b.append("\\b");case '\f'->b.append("\\f");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}}}return b.append('"').toString();}
}
