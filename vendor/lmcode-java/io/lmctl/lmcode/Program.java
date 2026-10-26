package io.lmctl.lmcode;

import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

public final class Program {
  public static final int VERSION=5;
  final List<VmString> consts=new ArrayList<>();
  final List<Registry.Binding> imports=new ArrayList<>();
  final List<String> contexts=new ArrayList<>();
  final List<Local> locals=new ArrayList<>();
  final List<Line> lines=new ArrayList<>();
  final Map<Integer,Loop> loops=new HashMap<>();
  byte[] code; int maxStack; Registry boundRegistry;
  private Program() {}
  public record Local(String typeName, boolean hidden) {}
  public record Line(int pc,long number,int column) {}
  public record Loop(int counter,int end) {}
  public static Program load(byte[] bytecode, byte[] schema, Registry registry) {
    Objects.requireNonNull(bytecode); Objects.requireNonNull(schema); Objects.requireNonNull(registry);
    Reader r=new Reader(bytecode); if (bytecode.length<4 || !Arrays.equals(r.bytes(4),new byte[]{'D','S','L','B'})) throw VmException.badBytecode("not a DSL bytecode file: bad magic (expected 'DSLB')");
    int version=r.u16(); if(version!=VERSION) throw VmException.badBytecode("unsupported bytecode version "+version+" (this runtime reads version "+VERSION+")");
    byte[] actual=r.bytes(32), expected=sha256(schema); if(!Arrays.equals(actual,expected)) throw new VmException("LOAD_SCHEMA_MISMATCH","schema mismatch: bytecode was compiled against schema sha256 "+hex(actual)+", the given schema has sha256 "+hex(expected));
    Program p=new Program(); long n=r.u32(); bounded(n,bytecode.length,"constant"); for(long i=0;i<n;i++){int tag=r.u8(); if(tag!=1) throw VmException.badBytecode("constant "+i+" has unknown tag "+tag+" (1 = string)"); long len=r.u32(); if(len>Integer.MAX_VALUE) throw VmException.badBytecode("constant "+i+" is too large"); p.consts.add(new VmString(r.bytes((int)len)));}
    n=r.u16(); bounded(n,bytecode.length,"import"); for(int i=0;i<n;i++){String s=p.ident((int)r.u32(),"import"); Registry.Binding b=registry.resolve(s); if(b==null) throw new VmException("LOAD_UNBOUND","import '"+s+"' is not provided by the host"+(registry.declaredFor(s).isEmpty()?"":" (the host declares "+registry.declaredFor(s)+")")); p.imports.add(b);}
    n=r.u16(); bounded(n,bytecode.length,"context"); for(int i=0;i<n;i++){String name=p.ident((int)r.u32(),"context name"), type=p.ident((int)r.u32(),"context type"); String have=registry.contextType(name); if(have==null) throw new VmException("LOAD_UNBOUND","context variable '"+name+"' is not declared by the host"); if(!have.equals(type)) throw new VmException("LOAD_UNBOUND","context variable '"+name+"' is declared as "+type+" by the bytecode, the host provides "+have); p.contexts.add(name);}
    n=r.u16(); bounded(n,bytecode.length,"local"); for(int i=0;i<n;i++){String type=p.ident((int)r.u32(),"local type"); int hidden=r.u8(); if(hidden>1) throw VmException.badBytecode("local "+i+" hidden flag must be 0 or 1"); p.locals.add(new Local(type,hidden==1));}
    p.maxStack=r.u16(); long codeLen=r.u32(); if(codeLen>Integer.MAX_VALUE) throw VmException.badBytecode("code section is too large"); p.code=r.bytes((int)codeLen); n=r.u32(); bounded(n,bytecode.length,"line table"); for(long i=0;i<n;i++){long pc=r.u32(), line=r.u32(); int col=r.u16(); if(pc>Integer.MAX_VALUE) throw VmException.badBytecode("line table entry points at pc "+pc+", beyond the code"); if(!p.lines.isEmpty()&&pc<p.lines.get(p.lines.size()-1).pc()) throw VmException.badBytecode("line table is not sorted by pc at entry "+i); p.lines.add(new Line((int)pc,line,col));}
    if(!r.done()) throw VmException.badBytecode("bytecode has "+(bytecode.length-r.pos)+" trailing byte(s) after the line table"); p.boundRegistry=registry; Verifier.verify(p); return p;
  }
  private String ident(int i,String what){if(i<0||i>=consts.size()) throw VmException.badBytecode(what+": constant index "+i+" out of range ("+consts.size()+" constants)"); try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(consts.get(i).bytes())).toString();}catch(CharacterCodingException e){throw new VmException("LOAD_UNBOUND",what+" is not a valid UTF-8 schema identifier");}}
  private static void bounded(long n,int bytes,String what){if(n>bytes)throw VmException.badBytecode(what+" table has too many entries: "+n);}
  private static byte[] sha256(byte[] x){try{return MessageDigest.getInstance("SHA-256").digest(x);}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
  private static String hex(byte[] b){StringBuilder s=new StringBuilder(); for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x)); return s.toString();}
  public int constantCount(){return consts.size();} public int importCount(){return imports.size();} public int localCount(){return locals.size();} public int codeSize(){return code.length;} public int maxStack(){return maxStack;}
  public List<Local> locals(){return List.copyOf(locals);} public List<Line> lines(){return List.copyOf(lines);}
  public Line lineOf(int pc){Line best=null; for(Line l:lines){if(l.pc()>pc)break;best=l;}return best;}
  public void run(Context context, Registry registry){Execution.run(this,context,boundRegistry!=null?boundRegistry:registry);}
}
