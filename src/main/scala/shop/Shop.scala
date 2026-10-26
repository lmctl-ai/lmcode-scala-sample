package shop

import io.lmctl.lmcode.{Context, Registry, Stdlib, Value, VmException, VmString}
import scala.collection.mutable.ArrayBuffer

/** Money is signed int64 cents. Equal contents are distinct objects, so handles point at instances. */
final class Money(var cents: Long)

/** The shop's priced items. The host owns the Money objects inside. */
final class Items(val values: Vector[Money])

final class Shop(val items: Items)

/** The ledger records entries and keeps a note. A note is bytes, not a Unicode String. */
final class Ledger:
  var balance: Long = 0L
  var entries: Long = 0L
  var note: VmString = VmString.fromString("")

object Shop:
  /** The schema bytes: printed by `--dsl-schema` and required exactly when a program is loaded. */
  lazy val schemaBytes: Array[Byte] =
    val stream = Shop.getClass.getResourceAsStream("/sample.dsl.json")
    if stream == null then throw IllegalStateException("sample.dsl.json is missing from the classpath")
    try stream.readAllBytes() finally stream.close()

  /** Round half away from zero, like the reference's std::round; keeps non-finite values unchanged. */
  private[shop] def roundHalfAway(x: Double): Double =
    if !x.isFinite then x
    else
      val magnitude = Math.abs(x)
      val floor = Math.floor(magnitude)
      Math.copySign(if magnitude - floor >= 0.5 then floor + 1.0 else floor, x)

/** The host owns every object bytecode can reach. `arena` keeps each Money alive for the process, so
  * handles stay valid until this Host is dropped.
  */
final class Host:
  /** The default registry installs the standard string, math, collection and file functions. */
  val registry: Registry = Registry()

  private val arena = ArrayBuffer.empty[Money]

  private def own(cents: Long): Money =
    val money = Money(cents)
    arena += money
    money

  val ledger: Ledger = Ledger()
  val shop: Shop = Shop(Items(Vector(own(125), own(250), own(375))))

  /** Fresh per run: the context names the live instances that the schema's `context` lines declare. */
  def context: Context =
    Context().set("shop", handle("Shop", shop)).set("ledger", handle("Ledger", ledger))

  // --- Binding helpers --------------------------------------------------------------------------

  private def params(names: String*): java.util.List[String] = java.util.List.of(names*)

  /** A host object as a tagged value. `object` is a Scala keyword, so the SDK's method needs backticks here. */
  private def handle(typeName: String, obj: AnyRef): Value = Value.`object`(typeName, obj)

  /** Turn a method receiver or argument back into the host object it was created from. */
  private def as[T](value: Value): T = value.asObject.asInstanceOf[T]

  private def money(cents: Long): Value = handle("Money", own(cents))

  /** A float price is in units; convert to cents with range checks, never a raw cast of an out-of-range double. */
  private def units(amount: Double): Value =
    val cents = Shop.roundHalfAway(amount * 100.0)
    if !cents.isFinite || cents < -9223372036854775808.0 || cents >= 9223372036854775808.0 then
      throw VmException.host("money: amount out of range")
    money(cents.toLong)

  // --- Registrations ------------------------------------------------------------------------------

  // Context names must match the schema. Standard testing (print, assert_close) is an explicit opt-in.
  registry.context("shop", "Shop").context("ledger", "Ledger")
  Stdlib.registerTesting(registry, System.out)

  // Distinct parameter lists are overloads: `int` means cents, `float` means units.
  registry.function("money", params("int"), "Money", (_, _, a) => money(a(0).asInt))
  registry.function("money", params("float"), "Money", (_, _, a) => units(a(0).asFloat))
  registry.function("fee", params("int"), "int", (_, _, a) => Value.ofInt(a(0).asInt + 5))
  registry.function("sample_tmp", params(), "string", (_, _, _) => {
    val path = sys.env.getOrElse("SAMPLE_TMP", "")
    if path.isEmpty then throw VmException.host("SAMPLE_TMP is required")
    Value.ofString(path)
  })

  // Money: arithmetic wraps modulo 2^64, which is what Java long arithmetic already does.
  registry.method("Money", "amount", params(), "int", (_, self, _) => Value.ofInt(as[Money](self).cents))
  registry.method("Money", "currency", params(), "Currency", (_, _, _) => Value.ofString("USD"))
  registry.method("Money", "plus", params("Money"), "Money", (_, self, a) =>
    money(as[Money](self).cents + as[Money](a(0)).cents))
  registry.method("Money", "minus", params("Money"), "Money", (_, self, a) =>
    money(as[Money](self).cents - as[Money](a(0)).cents))
  registry.method("Money", "times", params("int"), "Money", (_, self, a) =>
    money(as[Money](self).cents * a(0).asInt))
  registry.method("Money", "neg", params(), "Money", (_, self, _) => money(0L - as[Money](self).cents))
  registry.method("Money", "set", params("int"), "Money", (_, self, a) => {
    as[Money](self).cents = a(0).asInt
    self // The schema says returns:self, so the same receiver comes back.
  })

  // Collections need len() and at(int) for foreach; at() bounds-checks instead of trusting the index.
  registry.method("Items", "len", params(), "int", (_, self, _) =>
    Value.ofInt(as[Items](self).values.size.toLong))
  registry.method("Items", "at", params("int"), "Money", (_, self, a) => {
    val values = as[Items](self).values
    val index = a(0).asInt
    if index < 0 || index >= values.size then throw VmException.host("items: index out of range")
    handle("Money", values(index.toInt))
  })

  registry.method("Shop", "items", params(), "Items", (_, self, _) => handle("Items", as[Shop](self).items))
  registry.method("Shop", "total", params(), "Money", (_, self, _) =>
    money(as[Shop](self).items.values.foldLeft(0L)(_ + _.cents)))
  registry.method("Shop", "price", params("int"), "Money", (_, _, a) => money(a(0).asInt))
  registry.method("Shop", "price", params("float"), "Money", (_, _, a) => units(a(0).asFloat))
  registry.method("Shop", "currency", params(), "Currency", (_, _, _) => Value.ofString("USD"))
  registry.method("Shop", "label", params(), "string", (_, _, _) => Value.ofString("sample shop"))

  // A method can return its receiver (record), return a fresh object (balance) or return nothing (note).
  registry.method("Ledger", "record", params("Money"), "Ledger", (_, self, a) => {
    val ledger = as[Ledger](self)
    ledger.balance += as[Money](a(0)).cents
    ledger.entries += 1L
    self
  })
  registry.method("Ledger", "balance", params(), "Money", (_, self, _) => money(as[Ledger](self).balance))
  registry.method("Ledger", "count", params(), "int", (_, self, _) => Value.ofInt(as[Ledger](self).entries))
  registry.method("Ledger", "note", params("string"), "void", (_, self, a) => {
    as[Ledger](self).note = a(0).asString
    Value.none()
  })
  registry.method("Ledger", "note", params(), "string", (_, self, _) => Value.ofString(as[Ledger](self).note))
  // The VM attaches the DSL line and column to this error. The message is decoded as UTF-8 (see README).
  registry.method("Ledger", "reject", params("string"), "void", (_, _, a) =>
    throw VmException.host(a(0).asString.toString))
