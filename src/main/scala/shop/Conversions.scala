package shop

import io.lmctl.lmcode.{Context, Registry, Stdlib, Value, VmException, VmString}
import scala.jdk.CollectionConverters.*
import Bind.{handle, params}

/** Native conversion probes, shared by every port. They pin boundary behaviour that the shop domain never reaches:
  * int64 and binary64 round trips, raw bytes, handle identity, typed nulls, wrong-typed handles, standard
  * collection copies, and a long name and a 255-argument call. The contract is CONTRACT.md's conversion section.
  */
object Conversions:
  def register(registry: Registry, items: Items): Unit =
    // Scalars return their argument unchanged, so the boundary types must round-trip exactly.
    registry.function("host_int", params("int"), "int", (_, _, a) => Value.ofInt(a(0).asInt))
    registry.function("host_float", params("float"), "float", (_, _, a) => Value.ofFloat(a(0).asFloat))
    registry.function("host_bool", params("bool"), "bool", (_, _, a) => Value.ofBool(a(0).asBool))
    // Bytes stay bytes: never decode to a Java String and back, because invalid UTF-8 would change.
    registry.function("host_bytes", params("string"), "string", (_, _, a) => Value.ofString(a(0).asString))
    registry.function("host_hex", params("string"), "string", (_, _, a) => Value.ofString(hex(a(0).asString)))
    registry.function("host_float_case", params("string"), "float", (_, _, a) =>
      Value.ofFloat(floatCase(a(0).asString.toString)))

    // Handles: identity is object identity, not equal contents; null is a typed null Money.
    registry.function("host_echo_money", params("Money"), "Money", (_, _, a) => a(0))
    registry.function("host_same", params("Money", "Money"), "bool", (_, _, a) =>
      Value.ofBool(a(0).asObject eq a(1).asObject))
    registry.function("host_null", params(), "Money", (_, _, _) => handle("Money", null))
    registry.function("host_is_null", params("Money"), "bool", (_, _, a) => Value.ofBool(a(0).asObject eq null))
    registry.function("host_void", params(), "void", (_, _, _) => Value.none())
    // The declared return is Money, but the value is Items-tagged: a later Money receiver use must raise RUN_ERROR.
    registry.function("host_wrong", params(), "Money", (_, _, _) => handle("Items", items))

    // Standard collections: each host function copies its input into a new registry-owned object and appends a value.
    registry.function("host_str_list", params("StrList"), "StrList", (ctx, _, a) =>
      Stdlib.makeStrList(ctx, (strings(a(0)) :+ VmString.fromString("host")).asJava))
    registry.function("host_int_list", params("IntList"), "IntList", (ctx, _, a) =>
      Stdlib.makeIntList(ctx, (ints(a(0)) :+ 9L).map(v => java.lang.Long.valueOf(v)).asJava))
    registry.function("host_float_list", params("FloatList"), "FloatList", (ctx, _, a) =>
      Stdlib.makeFloatList(ctx, (floats(a(0)) :+ 0.5).map(v => java.lang.Double.valueOf(v)).asJava))
    registry.function("host_str_map", params("StrMap"), "StrMap", (ctx, _, a) => {
      val copy = java.util.LinkedHashMap[VmString, VmString](Stdlib.mapEntries(a(0)))
      copy.put(VmString.fromString("host"), VmString.fromString("yes")) // Keeps an existing key's position.
      Stdlib.makeStrMap(ctx, copy)
    })

    // Arity and name length are boundary cases: 255 arguments, and a 136-byte identifier.
    registry.function("host_sum", params(Seq.fill(255)("int")*), "int", (_, _, a) =>
      Value.ofInt(a.foldLeft(0L)((sum, value) => sum + value.asInt)))
    registry.function("host_long_" + "x" * 128, params("int"), "int", (_, _, a) => Value.ofInt(a(0).asInt))

  private def strings(collection: Value): List[VmString] =
    Stdlib.collectionValues(collection).asScala.map(_.asString).toList

  private def ints(collection: Value): List[Long] =
    Stdlib.collectionValues(collection).asScala.map(_.asInt).toList

  private def floats(collection: Value): List[Double] =
    Stdlib.collectionValues(collection).asScala.map(_.asFloat).toList

  /** Lowercase hexadecimal for every byte, including NUL and invalid UTF-8. */
  private def hex(text: VmString): String =
    text.bytes.map(b => f"${b & 0xff}%02x").mkString

  private def floatCase(name: String): Double = name match
    case "-0"        => -0.0
    case "nan"       => Double.NaN
    case "inf"       => Double.PositiveInfinity
    case "-inf"      => Double.NegativeInfinity
    case "large"     => 1e21
    case "subnormal" => java.lang.Double.MIN_VALUE
    case _           => throw VmException.host("host_float_case: unknown case")
