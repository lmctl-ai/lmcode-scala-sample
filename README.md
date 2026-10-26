# lmcode Scala shop sample

**Agent entry point:** a complete Scala 3 host for the shared lmcode shop corpus, not another interpreter.
Read these three files first: [domain and bindings](src/main/scala/shop/Shop.scala),
[CLI protocol](src/main/scala/shop/Main.scala), and [byte-exact output](src/main/scala/shop/Protocol.scala).
The SDK is the Java 21 `io.lmctl.lmcode` package, vendored unchanged in `vendor/lmcode-java/`.
You need a JDK 21, [sbt](https://www.scala-sbt.org/), Python 3 and `lmscript >= 0.2.420`.

```sh
npm install --global @lmctl-ai/lmctl@0.2.420
./test.sh
```

## Write a host in Scala in ten steps

1. **Choose a small domain.** `Shop` owns three priced items; `Ledger` records entries;
   `Money` is signed int64 cents. Read the [portable contract](sample-suite/CONTRACT.md).
2. **Declare the API.** [host.schema.json](sample-suite/host.schema.json) lists the contexts,
   object types, `Currency`, overloads and void methods. [sample.dsl.json](sample-suite/sample.dsl.json)
   merges that domain with the standard catalog. The host loads the schema from the classpath
   (`build.sbt` copies the file), so the bytes are exactly the corpus bytes.
3. **Pin dependencies.** [sdk.lock](sdk.lock) and [suite.lock](suite.lock) hash every vendored file;
   `python3 scripts/sync.py check` rejects drift. The SDK is compiled by sbt's `javac` from source.
4. **Own objects before exposing handles.** [Host](src/main/scala/shop/Shop.scala) keeps every `Money`
   in an arena (`ArrayBuffer`). A handle is `Value.object(type, obj)`; `as[T]` turns the receiver back
   into the Scala object. Objects live until the host is dropped.
5. **Register the schema's signatures.** Each `registry.function` / `registry.method` call is one
   overload; the parameter list is the identity. A Java lambda converts to `Registry.Callback` with
   Scala 3's SAM conversion: `(context, self, args) => Value`.
6. **Map language operations to methods.** Operators call `plus`, `minus`, `times`, `neg`; the declared
   conversion `int -> Money` calls `money(int)`. `Items.len`/`at` make foreach work. `Ledger.record` returns
   its receiver; `note(string)` returns `Value.none()`.
7. **Include standard and testing functions deliberately.** `new Registry()` installs the string, math,
   collection and file catalogs; `Stdlib.registerTesting` adds `print` and `assert_close` (an explicit opt-in).
   The [coverage audit](sample-suite/audit.py) checks all 72 standard signatures are imported by the corpus.
8. **Compile, never interpret source in the host.** The corpus wrappers use `_lmcode_`/`savedsl`; the
   `.dslb` bytecode is committed. The host loads it with `Program.load(bytes, schema, registry)`, and
   [`check-bytecode.py`](scripts/check-bytecode.py) recompiles to prove the pinned bytes reproduce.
9. **Handle errors at the right boundary.** [Main.scala](src/main/scala/shop/Main.scala) prints one JSON
   result after the program's trace lines: success 0, usage 2, load or run error 3, application failure 4.
   A `VmException` keeps its SDK `code`, line and column; loader wording is normalized. A negative final
   balance is an application rule, checked only after the VM succeeds.
10. **Run the shared integration test.** `test.sh` runs [run-suite.sh](sample-suite/run-suite.sh) with the
    command `java -cp <classpath> shop.Main`. Each case gets a fresh `SAMPLE_TMP`; stdout bytes and exit
    codes must match exactly, and stderr must be empty.

## Feature coverage

| Feature | Programs |
|---|---|
| Primitive literals, arithmetic, comparison, logic, if | 001–004 |
| While, range, inline function, switches | 005–008, 036, 039, 043, 045 |
| Host overloads, objects, operators, conversion, chaining, void methods | 009–014, 034–035, 040 |
| Pure host function `fee(int)` | 002 |
| Every standard collection method | 015–018, 037–038 |
| Every standard math overload and all 18 intrinsic math operations | 019–020, 025, 048 |
| Every standard string function and conversion | 021–023 |
| Every standard file function | 024 |
| Located VM and host errors, testing failure, application rule | 026–033 |
| int64 wrap and overflow, short-circuit, host foreach | 041–044 |
| Raw and dedented inner heredocs, all four outer splice types | 046–047 |
| Bad magic and schema-binding rejection | 090–091 |

The full feature-to-program matrix, with links to every source, bytecode and expectation, is in
[sample-suite/README.md](sample-suite/README.md).

## Conversions: lmcode type -> Scala

| lmcode type | Scala / SDK type | Rule |
|---|---|---|
| `int` | `Long` (`Value.asInt`, `Value.ofInt`) | int64; arithmetic wraps modulo 2^64, as Java `long` does. |
| `float` | `Double` (`asFloat`, `ofFloat`) | IEEE binary64; NaN and infinities are preserved, never cast to an integer unchecked. |
| `bool` | `Boolean` (`asBool`, `ofBool`) | Exactly `true` or `false`. |
| `string` | `VmString` (`asString`, `ofString`) | **Bytes, not `java.lang.String`.** Arbitrary bytes, including invalid UTF-8, round-trip. Decode with `toString` only for messages. |
| `Money`, `Items`, `Shop`, `Ledger` | any JVM object (`Value.object`, `asObject`) | A handle; the host keeps the object alive. Equal contents are distinct objects. |
| `Currency` (enum) | `string` value `"USD"` | Enums are string-backed at the boundary. |
| `void` | `Value.none()` | Return it from void methods. |
| `self` return | the same receiver `Value` | Return `self` to chain (`Money.set`, `Ledger.record`). |
| `StrList`, `IntList`, `StrMap`, ... | owned by the standard registry | Not used by this sample; the SDK creates and clears them per run. |
| Errors | `VmException(code, message)` | `VmException.host` is `RUN_HOST`; the VM adds `line`/`column`; `LOAD_*` come from loading. |

Output is written as raw bytes with LF endings (`Protocol.emit`), never through `println`, so a
platform newline or a Unicode conversion cannot change a byte of the reference output.

## Run and update

```sh
./test.sh                              # sync check, sbt build, corpus, bytecode, runner controls
SBT=/path/to/sbt ./test.sh             # choose an sbt binary explicitly
```

After a reviewed change in the canonical corpus (`lmctl-ai/lmcode-cpp/sample-suite`) or the SDK:

```sh
python3 scripts/sync.py suite ../lmcode-cpp      # pins the committed corpus HEAD, never a dirty tree
python3 scripts/sync.py sdk ../lmcode-java       # pins the committed SDK sources and std schema
./test.sh
```

Never edit anything under `vendor/` or `sample-suite/` here; sync from the canonical source instead.
The CI matrix runs `./test.sh` on Linux x64/arm64, macOS arm64/x64 and Windows x64 with lmscript 0.2.420.

## Notes for the next port

- The canonical `sample.dsl.json` declares 39 functions. The C++ reference also registers 17 extra
  `host_*` conversion functions that the schema does not declare, so its own protocol check fails.
  This port registers exactly the declared surface.
- Messages from `Ledger.reject` decode the byte string as UTF-8 (`VmException` takes a `String`). Corpus
  messages are ASCII; a port that must keep arbitrary bytes in error messages needs a byte-carrying error type.
