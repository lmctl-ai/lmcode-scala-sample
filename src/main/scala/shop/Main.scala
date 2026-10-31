package shop

import io.lmctl.lmcode.{Context, Formatting, InvocationResults, Program, Registry, Reports, ResultContract, ResultSink, Value, VmException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

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
  private val Usage = "usage: --version | --lmcode-schema [--json] | --lmcode-signatures | --lmcode FILE | [--lmcode|--first|--last] FILE ..."
  private def usage(): Int = Reports.failureFrame("cli", VmException("CLI_USAGE", Usage))

  def main(args: Array[String]): Unit =
    val status = run(args.toList)
    System.out.flush()
    System.exit(status)

  /** Dispatch on the exact argument shape; anything else is a usage error. */
  def run(args: List[String]): Int =
    args match
      case List("--version") => Protocol.line(Version); 0
      case List("--lmcode-schema") | List("--lmcode-schema", "--json") => Protocol.emit(Shop.schemaBytes); 0
      case List("--lmcode-signatures") => Host().registry.signatures().forEach(Protocol.line); 0
      case _ => runPrograms(args)

  private def runPrograms(args: List[String]): Int =
    if args.isEmpty || args.size % 2 != 0 then return usage()
    val pairs = args.grouped(2).toList
    if pairs.exists(p => !Set("--lmcode", "--first", "--last").contains(p.head)) then return usage()
    val files = pairs.map(_(1)).asJava
    val priorities = pairs.map(p => if p.head == "--first" then 0 else if p.head == "--last" then 2 else 1)
    val host = Host()
    try
      val results = InvocationResults.fromCommand(files)
      val schema = Shop.schemaBytes
      val contract = ResultContract.fromSchema(schema)
      val context = host.context
      var entriesCalled = 0
      var failed = false
      val order = pairs.indices.sortBy(priorities)
      for position <- order do
        val part = results.registrations().get(position)
        var phase = "load"
        try
          val program =
            try Program.load(readBytecode(part.file()), schema, host.registry)
            catch
              case error: VmException =>
                val message = error.code match
                  case "LOAD_SCHEMA_MISMATCH" => "sample schema mismatch"
                  case "LOAD_UNBOUND" => "sample import mismatch"
                  case _ => "invalid sample bytecode"
                throw VmException(error.code, message)
          results.select(part.index(), host.registry, contract)
          phase = "run"
          program.run(context, host.registry)
          for i <- 0 until program.entries().size() do
            program.entries().get(i).annotation() match
              case "item" =>
                for item <- host.itemHandles do
                  callEntry(program, i, context, host.registry, Bind.handle("Money", item))
                  entriesCalled += 1
              case "total" =>
                callEntry(program, i, context, host.registry, host.totalHandle)
                entriesCalled += 1
              case _ => ()
          results.complete(part.index())
        catch
          case error: VmException => results.fail(part.index(), phase, error); failed = true
          case _: Exception => results.fail(part.index(), "app", VmException("APP_FAIL", "cannot read sample input")); failed = true
      if !failed && host.ledger.balance < 0 then
        results.fail(null, "app", VmException("APP_FAIL", "ledger: negative balance"))
        failed = true
      if !failed then
        val summary = java.io.ByteArrayOutputStream()
        summary.writeBytes(s"{\"host_summary\":{\"balance\": ${host.ledger.balance}, \"entries\": ${host.ledger.entries}, \"note\": ".getBytes(UTF_8))
        summary.writeBytes(Protocol.jsonString(host.ledger.note.bytes))
        summary.writeBytes(s", \"entries_called\": $entriesCalled}}\n".getBytes(UTF_8))
        Protocol.emit(summary.toByteArray)
      results.writeFrame(System.out)
    catch
      case error: VmException => Reports.failureFrame("cli", error)
      case _: Exception => Reports.failureFrame("app", VmException("APP_FAIL", "cannot read sample input"))

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

  private def readBytecode(file: String): Array[Byte] =
    try Files.readAllBytes(Path.of(file))
    catch case _: Exception => throw IllegalArgumentException("cannot read sample input")

  private def concat(parts: Array[Byte]*): Array[Byte] =
    val out = java.io.ByteArrayOutputStream()
    parts.foreach(out.writeBytes)
    out.toByteArray
