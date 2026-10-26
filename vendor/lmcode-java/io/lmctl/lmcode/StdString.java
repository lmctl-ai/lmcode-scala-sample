package io.lmctl.lmcode;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

final class StdString {
  static final int MAX=64*1024*1024;
  private static VmString s(Value v){return v.asString();}
  static void register(Registry r){
    r.function("str_len", java.util.List.of("string"), "int", (c,self,a)->Value.ofInt(s(a[0]).length()));
    r.function("str_starts_with",java.util.List.of("string","string"),"bool",(c,x,a)->Value.ofBool(bytes(s(a[0]),s(a[1]))));
    r.function("str_ends_with",java.util.List.of("string","string"),"bool",(c,x,a)->Value.ofBool(ends(s(a[0]),s(a[1]))));
    r.function("str_contains",java.util.List.of("string","string"),"bool",(c,x,a)->Value.ofBool(index(s(a[0]),s(a[1]))>=0));
    r.function("str_find",java.util.List.of("string","string"),"int",(c,x,a)->Value.ofInt(index(s(a[0]),s(a[1]))));
    r.function("str_lower",java.util.List.of("string"),"string",(c,x,a)->Value.ofString(lower(s(a[0]))));
    r.function("str_upper",java.util.List.of("string"),"string",(c,x,a)->Value.ofString(upper(s(a[0]))));
    r.function("str_trim",java.util.List.of("string"),"string",(c,x,a)->Value.ofString(trim(s(a[0]))));
    r.function("str_substr",java.util.List.of("string","int","int"),"string",(c,x,a)->Value.ofString(substr(s(a[0]),a[1].asInt(),a[2].asInt())));
    r.function("str_repeat",java.util.List.of("string","int"),"string",(c,x,a)->Value.ofString(repeat(c,s(a[0]),a[1].asInt())));
    r.function("str_replace",java.util.List.of("string","string","string"),"string",(c,x,a)->Value.ofString(replace(c,s(a[0]),s(a[1]),s(a[2]))));
    r.function("str_is_int",java.util.List.of("string"),"bool",(c,x,a)->Value.ofBool(parseInt(s(a[0]))!=null));
    r.function("str_to_int",java.util.List.of("string"),"int",(c,x,a)->Value.ofInt(orZero(parseInt(s(a[0])))));
    r.function("str_is_float",java.util.List.of("string"),"bool",(c,x,a)->Value.ofBool(parseFloat(s(a[0]))!=null));
    r.function("str_to_float",java.util.List.of("string"),"float",(c,x,a)->Value.ofFloat(orZeroD(parseFloat(s(a[0])))));
    r.function("str_match",java.util.List.of("string","string"),"bool",(c,x,a)->Value.ofBool(glob(b(s(a[0])),b(s(a[1])))));
  }
  private static byte[] b(VmString x){return x.bytes();}
  private static boolean bytes(VmString a,VmString b){return starts(b(a),b(b));}
  private static boolean starts(byte[]a,byte[]b){return a.length>=b.length&&Arrays.equals(Arrays.copyOf(a,b.length),b);}
  private static boolean ends(VmString a,VmString b){byte[]x=b(a),y=b(b);if(x.length<y.length)return false;return Arrays.equals(Arrays.copyOfRange(x,x.length-y.length,x.length),y);}
  private static int index(VmString a,VmString n){byte[]x=b(a),y=b(n); outer:for(int i=0;i<=x.length-y.length;i++){for(int j=0;j<y.length;j++)if(x[i+j]!=y[j])continue outer;return i;}return -1;}
  private static VmString lower(VmString x){return map(x,false);} private static VmString upper(VmString x){return map(x,true);}
  private static VmString map(VmString x,boolean up){byte[]a=b(x).clone();for(int i=0;i<a.length;i++)if(up?a[i]>='a'&&a[i]<='z':a[i]>='A'&&a[i]<='Z')a[i]=(byte)(a[i]+(up?-32:32));return new VmString(a);}
  private static VmString trim(VmString x){byte[]a=b(x);int i=0,j=a.length;while(i<j&&ws(a[i]))i++;while(j>i&&ws(a[j-1]))j--;return new VmString(Arrays.copyOfRange(a,i,j));}
  private static boolean ws(byte c){return c==' '||(c>=9&&c<=13);}
  private static VmString substr(VmString x,long st,long n){byte[]a=b(x);if(st<0)st=0;if(n<=0||st>=a.length)return new VmString(new byte[0]);long end=n>Long.MAX_VALUE-st?Long.MAX_VALUE:st+n;int i=(int)st,j=(int)Math.min((long)a.length,end);return new VmString(Arrays.copyOfRange(a,i,j));}
  private static VmString repeat(Context c,VmString x,long n){byte[]a=b(x);if(n<0)throw VmException.host("str_repeat: count must be nonnegative and result at most 67108864 bytes");if(a.length==0)return new VmString(new byte[0]);if(n>MAX/a.length)throw VmException.host("str_repeat: count must be nonnegative and result at most 67108864 bytes");long bytes=(long)a.length*n;c.charge(bytes);byte[]o=new byte[(int)bytes];for(int i=0;i<n;i++)System.arraycopy(a,0,o,i*a.length,a.length);return new VmString(o);}
  private static VmString replace(Context c,VmString x,VmString f,VmString t){byte[]a=b(x),q=b(f),z=b(t);if(q.length==0)return x;long count=0;for(int at=0;at<=a.length-q.length;){int k=indexAt(a,at,q);if(k<0)break;count++;at=k+q.length;}long out=(long)a.length+count*((long)z.length-q.length);if(out<0||out>MAX)throw VmException.host("str_replace: result must be at most 67108864 bytes");c.charge(out);java.io.ByteArrayOutputStream o=new java.io.ByteArrayOutputStream((int)out);int at=0;while(at<=a.length){int k=indexAt(a,at,q);if(k<0){o.write(a,at,a.length-at);break;}o.write(a,at,k-at);o.writeBytes(z);at=k+q.length;}return new VmString(o.toByteArray());}
  private static int indexAt(byte[]a,int at,byte[]q){outer:for(int i=at;i<=a.length-q.length;i++){for(int j=0;j<q.length;j++)if(a[i+j]!=q[j])continue outer;return i;}return -1;}
  private static Long parseInt(VmString x){try{String z=new String(b(x),StandardCharsets.US_ASCII);if(!z.matches("[+-]?[0-9]+"))return null;return Long.parseLong(z);}catch(Exception e){return null;}}
  private static Double parseFloat(VmString x){try{String z=new String(b(x),StandardCharsets.US_ASCII);if(!z.matches("[+-]?(([0-9]+(\\.[0-9]*)?)|(\\.[0-9]+))([eE][+-]?[0-9]+)?"))return null;double d=Double.parseDouble(z);if(!Double.isFinite(d))return null;if(d==0){int e=z.indexOf('e');if(e<0)e=z.indexOf('E');String significand=e<0?z:z.substring(0,e);for(int i=0;i<significand.length();i++)if(significand.charAt(i)>='1'&&significand.charAt(i)<='9')return null;}return d;}catch(Exception e){return null;}}
  private static long orZero(Long x){return x==null?0:x;} private static double orZeroD(Double x){return x==null?0:x;}
  private static boolean glob(byte[] text,byte[] pat){
    int t=0,p=0,star=-1,mark=0;long[] work={0};int[] next={0};
    while(t<text.length){step(work);if(p<pat.length&&pat[p]=='*'){star=p++;mark=t;}
      else if(p<pat.length&&matchToken(pat,p,text[t]&255,next,work)){p=next[0];t++;}
      else if(star>=0){p=star+1;t=++mark;}else return false;}
    while(p<pat.length&&pat[p]=='*'){step(work);p++;}return p==pat.length;
  }
  private static void step(long[] work){if(work[0]==(1L<<27))throw VmException.host("str_match: pattern exceeds the 2^27 work limit");work[0]++;}
  private static boolean matchToken(byte[] p,int i,int c,int[] next,long[] work){
    if(p[i]=='\\'&&i+1<p.length){next[0]=i+2;return(p[i+1]&255)==c;}
    if(p[i]=='?'){next[0]=i+1;return true;}
    if(p[i]=='['){int q=i+1;boolean negate=q<p.length&&p[q]=='!';if(negate)q++;int first=q;boolean hit=false;
      while(q<p.length&&(p[q]!=']'||q==first)){step(work);int lo=p[q++]&255;if(lo=='\\'&&q<p.length)lo=p[q++]&255;
        if(q+1<p.length&&p[q]=='-'&&p[q+1]!=']'){q++;int hi=p[q++]&255;if(hi=='\\'&&q<p.length)hi=p[q++]&255;if(lo<=hi&&c>=lo&&c<=hi)hit=true;}
        else if(lo==c)hit=true;}
      if(q<p.length){next[0]=q+1;return hit!=negate;}
    }
    next[0]=i+1;return(p[i]&255)==c;
  }
}
