package shop

import io.lmctl.lmcode.{Program, VmException}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/** The native host protocol. Compilation belongs to lmscript; this program only executes bytecode.
  *
  * Exit codes: 0 success, 2 usage, 3 load or run error, 4 application failure.
  */
object Main:
  private val Version = "lmcode-scala-sample 0.1.0"
  private val Usage = "usage: --version | --dsl-schema [--json] | --dsl-signatures | --dsl FILE"

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
      case List("--dsl-schema") | List("--dsl-schema", "--json") =>
        Protocol.emit(Shop.schemaBytes)
        0
      case List("--dsl-signatures") =>
        Host().registry.signatures.forEach(signature => Protocol.line(signature))
        0
      case List("--dsl", file) => runProgram(file)
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

  /** Run one loaded program. Its trace lines were already written by `print`; the result line comes last. */
  private def runLoaded(program: Program, host: Host): Int =
    try program.run(host.context, host.registry)
    catch
      case error: VmException =>
        Protocol.vmFailure("run", error, error.getMessage.getBytes(UTF_8))
        return 3
    // A negative balance is an application rule, checked only after the VM succeeded.
    if host.ledger.balance < 0 then return appFailure("ledger: negative balance")
    val note = Protocol.jsonString(host.ledger.note.bytes)
    val result = s"{\"balance\": ${host.ledger.balance}, \"entries\": ${host.ledger.entries}, \"note\": "
    Protocol.ok(concat(result.getBytes(UTF_8), note, "}".getBytes(UTF_8)))
    0

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
