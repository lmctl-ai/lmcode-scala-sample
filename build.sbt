// Scala 3 shop host. The Java SDK is vendored verbatim (scripts/sync.py) and compiled alongside
// this project by sbt's javac; there are no other dependencies beyond the Scala library.
ThisBuild / scalaVersion := "3.3.6"
ThisBuild / version := "0.1.0"

// test.sh runs the corpus with `java -cp <file contents> shop.Main`. A task writes the classpath to a
// file, so no parsing of sbt's console output is needed.
val writeRuntimeClasspath = taskKey[File]("Write the runtime classpath for the corpus runner")

lazy val sample = (project in file("."))
  .settings(
    name := "lmcode-scala-sample",
    Compile / scalaSource := baseDirectory.value / "src" / "main" / "scala",
    Compile / javaSource := baseDirectory.value / "vendor" / "lmcode-java",
    Compile / javacOptions ++= Seq("--release", "21", "-encoding", "UTF-8"),
    scalacOptions ++= Seq("-deprecation", "-feature", "-Werror"),
    Compile / mainClass := Some("shop.Main"),
    // The schema the host prints and loads is the corpus file's exact bytes, copied onto the classpath.
    Compile / resourceGenerators += Def.task {
      val source = baseDirectory.value / "sample-suite" / "sample.dsl.json"
      val target = (Compile / resourceManaged).value / "sample.dsl.json"
      IO.copyFile(source, target)
      val versionFile = (Compile / resourceManaged).value / "sample-version.txt"
      IO.write(versionFile, sys.env.getOrElse("SAMPLE_VERSION", "0.4.0") + "\n")
      Seq(target, versionFile)
    }.taskValue,
    writeRuntimeClasspath := {
      val file = target.value / "runtime-classpath.txt"
      IO.write(file, (Runtime / fullClasspath).value.map(_.data.getAbsolutePath).mkString(java.io.File.pathSeparator))
      file
    }
  )
