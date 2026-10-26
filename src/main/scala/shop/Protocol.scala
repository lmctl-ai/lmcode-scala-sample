package shop

import io.lmctl.lmcode.VmException
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.UTF_8

/** The CLI wire format. Every line is LF-terminated raw bytes, so string values never pass through a
  * Unicode or platform newline conversion. Output must match the reference byte for byte.
  */
object Protocol:
  /** Quote and escape bytes exactly like the reference: `"`, `\`, `\n`, `\r`, `\t`, other controls as
    * `\u00xx`; every other byte (including invalid UTF-8) is copied unchanged.
    */
  def jsonString(bytes: Array[Byte]): Array[Byte] =
    val out = ByteArrayOutputStream()
    out.write('"')
    for b <- bytes do
      (b & 0xff) match
        case '"'           => out.writeBytes("\\\"".getBytes(UTF_8))
        case '\\'          => out.writeBytes("\\\\".getBytes(UTF_8))
        case '\n'          => out.writeBytes("\\n".getBytes(UTF_8))
        case '\r'          => out.writeBytes("\\r".getBytes(UTF_8))
        case '\t'          => out.writeBytes("\\t".getBytes(UTF_8))
        case c if c < 0x20 => out.writeBytes(String.format("\\u%04x", c).getBytes(UTF_8))
        case c             => out.write(c)
    out.write('"')
    out.toByteArray

  /** Write raw bytes and flush, so trace lines printed by the program keep their order with the result. */
  def emit(bytes: Array[Byte]): Unit =
    System.out.write(bytes)
    System.out.flush()

  /** One plain line of protocol text (ASCII), such as `--version` or a signature. */
  def line(text: String): Unit = emit((text + "\n").getBytes(UTF_8))

  /** `{"ok": true, "result": ...}`; `resultJson` is already valid JSON. */
  def ok(resultJson: Array[Byte]): Unit =
    emit(join(text("{\"ok\": true, \"result\": "), resultJson, text("}\n")))

  /** `{"ok": false, "phase": ..., "code": ..., "message": ...}`, with line and column appended when known. */
  def failure(phase: String, code: String, message: Array[Byte], line: Option[Long] = None, column: Option[Int] = None): Unit =
    val location = line.fold("")(n => s", \"line\": $n") + column.fold("")(c => s", \"column\": $c")
    emit(join(
      text("{\"ok\": false, \"phase\": "), jsonString(phase.getBytes(UTF_8)),
      text(", \"code\": "), jsonString(code.getBytes(UTF_8)),
      text(", \"message\": "), jsonString(message),
      text(location + "}\n")))

  /** A VM error from load or run, carrying the SDK's code and DSL location. */
  def vmFailure(phase: String, error: VmException, message: Array[Byte]): Unit =
    failure(phase, error.code, message, Option(error.line).map(_.longValue), Option(error.column).map(_.intValue))

  private def text(s: String): Array[Byte] = s.getBytes(UTF_8)

  private def join(parts: Array[Byte]*): Array[Byte] =
    val out = ByteArrayOutputStream()
    parts.foreach(out.writeBytes)
    out.toByteArray
