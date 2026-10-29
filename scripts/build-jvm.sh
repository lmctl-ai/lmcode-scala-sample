#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p target/generated-resources build
printf '%s\n' "${SAMPLE_VERSION:-0.4.0}" > target/generated-resources/sample-version.txt
"${SBT:-sbt}" -batch compile writeRuntimeClasspath
