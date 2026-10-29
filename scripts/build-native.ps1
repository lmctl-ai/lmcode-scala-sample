$ErrorActionPreference = "Stop"
$classpath = (Get-Content target/runtime-classpath.txt -Raw).Trim()
$argsNative = @("-H:+UnlockExperimentalVMOptions", "-J-Xmx4g", "-H:NumberOfThreads=2", "--no-fallback", "-march=compatibility", "-H:+ReportExceptionStackTraces", "-H:IncludeResources=sample\.dsl\.json|sample-version\.txt", "-cp", $classpath, "shop.Main", "build/lmcode-scala-sample")
& "$env:JAVA_HOME/bin/native-image.cmd" @argsNative
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
