package io.lmctl.lmcode;

import java.util.List;

/** Focused collection contract checks shared by the JVM and native self-tests. */
public final class CollectionContractChecks {
  private CollectionContractChecks() {}

  public static int run() {
    mapRemovalKeepsLiveInsertionOrder();
    floatListsUseNumericEquality();
    handlesExpireAtRunExit();
    return 3;
  }

  private static void mapRemovalKeepsLiveInsertionOrder() {
    Registry r = new Registry(true);
    Context c = new Context();
    r.enterRun(c);
    try {
      Value map = call(r, "fn str_map() -> StrMap", c, Value.none());
      call(r, "method StrMap.set(string, string) -> StrMap", c, map, s("a"), s("one"));
      call(r, "method StrMap.set(string, string) -> StrMap", c, map, s("b"), s("two"));
      call(r, "method StrMap.set(string, string) -> StrMap", c, map, s("a"), s("updated"));
      require(call(r, "method StrMap.key_at(int) -> string", c, map, i(0)).asString().equals(s("a").asString()), "map update retains position");
      require(call(r, "method StrMap.remove(string) -> bool", c, map, s("a")).asBool(), "map remove reports true");
      call(r, "method StrMap.set(string, string) -> StrMap", c, map, s("c"), s("three"));
      require(call(r, "method StrMap.key_at(int) -> string", c, map, i(0)).asString().equals(s("b").asString()), "remaining key moves to live rank zero");
      require(call(r, "method StrMap.key_at(int) -> string", c, map, i(1)).asString().equals(s("c").asString()), "new key appends after surviving keys");
    } finally { r.exitRun(c); }
  }

  private static void floatListsUseNumericEquality() {
    Registry r = new Registry(true);
    Context c = new Context();
    r.enterRun(c);
    try {
      Value list = call(r, "fn float_list() -> FloatList", c, Value.none());
      call(r, "method FloatList.add(float) -> FloatList", c, list, f(-0.0));
      require(call(r, "method FloatList.contains(float) -> bool", c, list, f(0.0)).asBool(), "signed zero compares numerically");
      require(call(r, "method FloatList.index_of(float) -> int", c, list, f(0.0)).asInt() == 0, "signed zero index uses numeric equality");
      call(r, "method FloatList.add(float) -> FloatList", c, list, f(Double.NaN));
      require(!call(r, "method FloatList.contains(float) -> bool", c, list, f(Double.NaN)).asBool(), "NaN does not compare equal");
    } finally { r.exitRun(c); }
  }

  private static void handlesExpireAtRunExit() {
    Registry r = new Registry(true);
    Context c = new Context(); Value list;
    r.enterRun(c);
    try { list = call(r, "fn str_list() -> StrList", c, Value.none()); }
    finally { r.exitRun(c); }
    boolean expired = false;
    try { call(r, "method StrList.len() -> int", c, list); }
    catch (VmException e) { expired = "RUN_HOST".equals(e.code); }
    require(expired, "collection handles expire at run exit");
  }

  private static Value call(Registry r, String signature, Context c, Value... args) {
    Registry.Binding b = r.resolve(signature);
    require(b != null, "missing collection signature: " + signature);
    Value self = Value.none();
    Value[] actual = args;
    if (b.method) {
      require(args.length > 0, "method call needs self: " + signature);
      self = args[0];
      actual = java.util.Arrays.copyOfRange(args, 1, args.length);
    }
    return b.callback.call(c, self, actual);
  }
  private static Value i(long x) { return Value.ofInt(x); }
  private static Value f(double x) { return Value.ofFloat(x); }
  private static Value s(String x) { return Value.ofString(x); }
  private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
