# Third-party notices

The sample host is MIT licensed (see [LICENSE](LICENSE)). It vendors and depends on:

| Component | Version | Licence | Where |
| --- | --- | --- | --- |
| lmcode-java SDK (`io.lmctl.lmcode`, Java 21) | commit in [`sdk.lock`](sdk.lock) | MIT | `vendor/lmcode-java/` (copied by `scripts/sync.py`) |
| lmcode shop corpus (`sample-suite/`) | commit in [`suite.lock`](suite.lock) | MIT | `sample-suite/` (copied by `scripts/sync.py`) |
| Scala 3 library | 3.3.6 | Apache-2.0 | resolved by sbt at build time; not vendored |
| sbt | 1.10.11 | Apache-2.0 | build tool only; not part of the program |

The vendored files are byte-identical to the pinned commits; `scripts/sync.py check` fails on any change.
The Scala library and sbt are build-time inputs fetched from their public repositories; the program
links only the Scala runtime library.
