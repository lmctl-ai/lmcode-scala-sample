#!/usr/bin/env bash
# Build the Scala host from the vendored sources, then run the canonical corpus checks: pinned locks, the
# coverage audit, every corpus case byte-for-byte, compiler reproduction and the runner's controls.
set -euo pipefail
cd "$(dirname "$0")"
SBT="${SBT:-sbt}"

python3 -B scripts/sync.py check
"$SBT" -batch compile
"$SBT" -batch writeRuntimeClasspath
classpath="$(cat target/runtime-classpath.txt)"
test -n "$classpath" || { echo "the runtime classpath is empty" >&2; exit 1; }

python3 -B sample-suite/audit.py --std vendor/lmcode-java/std/lmcode-std.schema.json
bash sample-suite/run-suite.sh java -cp "$classpath" shop.Main
python3 -B scripts/check-bytecode.py
python3 -B sample-suite/test_runner.py
