#!/usr/bin/env bash
# Build the Scala host from the vendored sources, then run every check the C++ reference runs:
# pinned locks, the coverage audit, the shared corpus (all 50 cases, byte-exact), compiler reproducibility
# and the runner's negative controls. Requires sbt and python3; the corpus check also needs lmscript.
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
