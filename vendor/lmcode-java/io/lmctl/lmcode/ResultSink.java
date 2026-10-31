package io.lmctl.lmcode;

import java.util.LinkedHashMap;
import java.util.Map;

/** One host-owned logical invocation, outside the standard per-run arenas.
 * Reset before a NEW program, never between body and selected entry calls.
 * Conversion completes before assignment, so a refused write preserves the root.
 */
public final class ResultSink {
  private Object root;
  public void reset() { root = null; }
  public void setNull() { reset(); }
  public void set(Value value) { Object owned = ResultJson.copy(value, ""); root = owned; }
  private void objectRoot() {
    if (root != null && !(root instanceof Map<?,?>))
      throw VmException.host("lmcode return: field assignment requires object or null");
  }
  private String fieldKey(VmString key) { objectRoot(); return ResultJson.text(key, ""); }
  @SuppressWarnings("unchecked") private void putOwned(String key, Object value) {
    if (root == null) root = new LinkedHashMap<String,Object>();
    ((Map<String,Object>)root).put(key, value); // LinkedHashMap overwrite keeps position
  }
  public void put(VmString key, Value value) {
    String name = fieldKey(key);
    Object owned = ResultJson.copy(value, ResultJson.pointer("", name));
    putOwned(name, owned);
  }
  public void put(String key, Value value) { objectRoot(); put(ResultJson.key(key), value); }
  public void putNull(VmString key) { String name = fieldKey(key); putOwned(name, null); }
  public void putNull(String key) { objectRoot(); putNull(ResultJson.key(key)); }
  public String json() { return ResultJson.encode(root); }
}
