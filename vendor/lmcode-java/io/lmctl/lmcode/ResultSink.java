package io.lmctl.lmcode;

import java.util.LinkedHashMap;
import java.util.Map;

/** One owned program result, retained across its body and selected entry calls. */
public final class ResultSink {
  private final ResultContract contract;
  private Object root;
  private boolean isSealed;
  public ResultSink(){this(new ResultContract());}
  public ResultSink(ResultContract contract){this.contract=java.util.Objects.requireNonNull(contract);}
  public ResultContract contract(){return contract;}
  public boolean isSealed(){return isSealed;}
  private void writable(){if(isSealed)throw VmException.host("lmcode return: result sink is sealed");}
  public void reset(){writable();root=null;}
  public void setNull(){writable();contract.validate(null);root=null;}
  public void set(Value value){writable();Object owned=ResultJson.copy(value,"");contract.validateSource(value);contract.validate(owned);root=owned;}
  private void objectRoot(){writable();if(root!=null&&!(root instanceof Map<?,?>))throw VmException.host("lmcode return: field assignment requires object or null");}
  private String fieldKey(VmString key){objectRoot();return ResultJson.text(key,"");}
  @SuppressWarnings("unchecked") private void putOwned(String key,Object value){
    Map<String,Object> next=root==null?new LinkedHashMap<>():new LinkedHashMap<>((Map<String,Object>)root);
    next.put(key,value);contract.validate(next);root=next;
  }
  public void put(VmString key,Value value){String name=fieldKey(key);Object owned=ResultJson.copy(value,ResultJson.pointer("",name));contract.validateFieldSource(name,value);putOwned(name,owned);}
  public void put(String key,Value value){objectRoot();put(ResultJson.key(key),value);}
  public void putNull(VmString key){String name=fieldKey(key);putOwned(name,null);}
  public void putNull(String key){objectRoot();putNull(ResultJson.key(key));}
  public String json(){return ResultJson.encode(root);}
  Object seal(){writable();contract.validate(root);Object copy=InvocationJson.snapshot(root);isSealed=true;return copy;}
}
