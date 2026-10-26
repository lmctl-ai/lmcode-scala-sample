package io.lmctl.lmcode;
public final class Reports {
 private Reports(){}
 public static String escape(String s){StringBuilder b=new StringBuilder("\"");for(int i=0;i<s.length();i++){char c=s.charAt(i);switch(c){case '"'->b.append("\\\"");case '\\'->b.append("\\\\");case '\n'->b.append("\\n");case '\r'->b.append("\\r");case '\t'->b.append("\\t");default->{if(c<32)b.append(String.format(java.util.Locale.ROOT,"\\u%04x",(int)c));else b.append(c);}}}return b.append('"').toString();}
 public static int ok(String resultJson){System.out.println("{\"ok\": true, \"result\": "+resultJson+"}");return 0;}
 public static String errorJson(String phase,VmException e){return "{\"ok\": false, \"phase\": "+escape(phase)+", \"code\": "+escape(e.code)+", \"message\": "+escape(e.getMessage())+(e.line==null?"":", \"line\": "+e.line+(e.column==null?"":", \"column\": "+e.column))+"}";}
 public static int error(String phase,VmException e){System.out.println(errorJson(phase,e));return e.code.equals("APP_FAIL")?4:3;}
 public static int appFailure(String message){return error("app",new VmException("APP_FAIL",message));}
}
