$ErrorActionPreference = "Stop"
$classpath = (Get-Content target/runtime-classpath.txt -Raw).Trim()
$argsNative = @("-H:+UnlockExperimentalVMOptions", "-J-Xmx4g", "-H:NumberOfThreads=2", "--no-fallback", "-march=compatibility", "-H:+ReportExceptionStackTraces", "-H:IncludeResources=sample\.dsl\.json", "-H:IncludeResources=sample-version\.txt", "-cp", $classpath, "shop.Main", "build/lmcode-scala-sample")
$nativeImage = Join-Path $env:JAVA_HOME "bin/native-image.cmd"
if (!(Test-Path $nativeImage)) { throw "GraalVM native-image.cmd is required" }
& $nativeImage @argsNative
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
