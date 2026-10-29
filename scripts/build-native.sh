#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
case "$(uname -s)" in
  MSYS*|MINGW*|CYGWIN*) powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/build-native.ps1; exit $?;;
esac
flags=(-H:+UnlockExperimentalVMOptions -J-Xmx4g -H:NumberOfThreads=2 --no-fallback -march=compatibility -H:+ReportExceptionStackTraces '-H:IncludeResources=sample\.dsl\.json' '-H:IncludeResources=sample-version\.txt')
if [[ "$(uname -s)" == Linux* ]]; then
  flags+=('-H:+StaticExecutableWithDynamicLibC' '-H:NativeLinkerOption=-static-libstdc++' '-H:NativeLinkerOption=-static-libgcc')
fi
native-image "${flags[@]}" -cp "$(cat target/runtime-classpath.txt)" shop.Main "build/lmcode-scala-sample"
