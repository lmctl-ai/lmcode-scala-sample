package shop

import io.lmctl.lmcode.{Value, VmException}

/** Small helpers shared by every binding file. They are the whole interface between the Scala domain and the
  * SDK's tagged values: a parameter list is a Java `List<String>`, a handle is a tagged object, and a receiver
  * or argument is turned back into the Scala object it wraps.
  */
object Bind:
  /** A declared parameter list. Overloads are distinguished by it, so `params("int")` and `params("float")` differ. */
  def params(names: String*): java.util.List[String] = java.util.List.of(names*)

  /** A host object as a tagged value. `object` is a Scala keyword, so the SDK's factory needs backticks. A null
    * object is a typed null handle; the SDK allows it, and host code decides what a method does with it.
    */
  def handle(typeName: String, obj: AnyRef): Value = Value.`object`(typeName, obj)

  /** Recover the Scala object behind a handle; the SDK has already checked the declared type. */
  def as[T](value: Value): T = value.asObject.asInstanceOf[T]

  /** A Money receiver or argument; a null handle is a host error, not a Scala NullPointerException. */
  def moneyOf(value: Value): Money =
    val money = as[Money](value)
    if money == null then throw VmException.host("money: null handle")
    money
