package shop

import io.lmctl.lmcode.{Context, Formatting, Program, Registry, Reports, ResultSink, Value, VmException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** The native host protocol. Compilation belongs to lmscript; this program only executes bytecode.
  *
  * Exit codes: 0 success, 2 usage, 3 load or run error, 4 application failure.
  */
object Main:
  private val Version = {
    val in = getClass.getResourceAsStream("/sample-version.txt")
    if in == null then throw IllegalStateException("missing sample-version.txt")
    try "lmcode-scala-sample " + String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
    finally in.close()
  }
  private val Usage = "usage: --version | --lmcode-schema [--json] | --lmcode-signatures | --lmcode FILE"

  def main(args: Array[String]): Unit =
    val status = run(args.toList)
    System.out.flush()
    System.exit(status)

  /** Dispatch on the exact argument shape; anything else is a usage error. */
  def run(args: List[String]): Int =
    args match
      case List("--version") =>
        Protocol.line(Version)
        0
      case List("--lmcode-schema") | List("--lmcode-schema", "--json") =>
        Protocol.emit(Shop.schemaBytes)
        0
      case List("--lmcode-signatures") =>
        Host().registry.signatures.forEach(signature => Protocol.line(signature))
        0
      case List("--lmcode", file) => runProgram(file)
      case _ =>
        Protocol.failure("cli", "CLI_USAGE", Usage.getBytes(UTF_8))
        2

  private def runProgram(file: String): Int =
    val bytecode = try readBytecode(file) catch case _: AppFailure => return appFailure("cannot read sample input")
    val host = Host()
    val program =
      try Program.load(bytecode, Shop.schemaBytes, host.registry)
      catch
        case error: VmException => return loadFailure(error)
        case _: RuntimeException => return appFailure("cannot read sample input")
    runLoaded(program, host)

  /** Run one loaded program. Its trace lines were already written by `print`; the terminal frame follows the diagnostic result line. */
  private def runLoaded(program: Program, host: Host): Int =
    // The invocation sink survives the body and selected rule-entry arena resets.
    val sink = ResultSink()
    host.registry.setResultSink(sink)
    val context = host.context
    try program.run(context, host.registry)
    catch
      case error: VmException =>
        Protocol.vmFailure("run", error, error.getMessage.getBytes(UTF_8))
        return 3
    // Rule entries (DSLB v6): after the body, @item entries run once per shop
    // item in order, @total once with the shop total, other annotations never.
    var entriesCalled = 0
    val entries = program.entries()
    try
      for i <- 0 until entries.size() do
        entries.get(i).annotation() match
          case "item" =>
            for item <- host.itemHandles do
              callEntry(program, i, context, host.registry, Bind.handle("Money", item))
              entriesCalled += 1
          case "total" =>
            callEntry(program, i, context, host.registry, host.totalHandle)
            entriesCalled += 1
          case _ => ()
    catch
      case error: VmException =>
        Protocol.vmFailure("run", error, error.getMessage.getBytes(UTF_8))
        return 3
    // A negative balance is an application rule, checked only after the VM succeeded.
    if host.ledger.balance < 0 then return appFailure("ledger: negative balance")
    val note = Protocol.jsonString(host.ledger.note.bytes)
    val result = s"{\"balance\": ${host.ledger.balance}, \"entries\": ${host.ledger.entries}, \"note\": "
    Protocol.ok(concat(result.getBytes(UTF_8), note, s", \"entries_called\": $entriesCalled}".getBytes(UTF_8)))
    try Reports.writeResultFrame(System.out, sink)
    catch case _: java.io.IOException => return appFailure("cannot write result frame")
    0

  /** Call rule entry `index` with one argument; a non-void result prints `entry NAME = VALUE`. */
  private def callEntry(program: Program, index: Int, context: Context, registry: Registry, argument: Value): Unit =
    val result = program.call(index, context, registry, java.util.List.of(argument))
    if result.kind() != Value.Kind.NONE then
      Protocol.emit(concat(
        "entry ".getBytes(UTF_8),
        program.entries().get(index).name().getBytes(UTF_8),
        " = ".getBytes(UTF_8),
        valueText(result),
        "\n".getBytes(UTF_8)))

  /** Same text as the language's str(): int decimal, float %.15g semantics, bool true/false, string raw bytes. */
  private def valueText(value: Value): Array[Byte] =
    value.kind() match
      case Value.Kind.INT   => value.asInt.toString.getBytes(UTF_8)
      case Value.Kind.FLOAT => Formatting.formatG15(value.asFloat).getBytes(UTF_8)
      case Value.Kind.BOOL  => (if value.asBool then "true" else "false").getBytes(UTF_8)
      case Value.Kind.STR   => value.asString.bytes
      case _                => Array.emptyByteArray

  /** Loader wording is normalized so every port reports the same portable message. */
  private def loadFailure(error: VmException): Int =
    val message = error.code match
      case "LOAD_SCHEMA_MISMATCH" => "sample schema mismatch"
      case "LOAD_UNBOUND"         => "sample import mismatch"
      case _                      => "invalid sample bytecode"
    Protocol.failure("load", error.code, message.getBytes(UTF_8))
    3

  private def appFailure(message: String): Int =
    Protocol.failure("app", "APP_FAIL", message.getBytes(UTF_8))
    4

  /** Thrown only for I/O; reported as an application failure (exit 4), never a VM error. */
  private final class AppFailure extends RuntimeException

  private def readBytecode(file: String): Array[Byte] =
    try Files.readAllBytes(Path.of(file))
    catch case _: Exception => throw AppFailure()

  private def concat(parts: Array[Byte]*): Array[Byte] =
    val out = java.io.ByteArrayOutputStream()
    parts.foreach(out.writeBytes)
    out.toByteArray
