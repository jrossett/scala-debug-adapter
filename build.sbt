import sbt.CrossVersion
import sbt.Keys.crossVersion

import java.io.File
import scala.collection.mutable

def isRelease() =
  System.getenv("GITHUB_REPOSITORY") == "scalacenter/scala-debug-adapter" &&
    System.getenv("GITHUB_WORKFLOW") == "Release"

inThisBuild(
  Seq(
    organization := "ch.epfl.scala",
    homepage := Some(url("https://github.com/scalacenter/scala-debug-adapter")),
    onLoadMessage := s"Welcome to scala-debug-adapter ${version.value}",
    licenses := List(
      "Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")
    ),
    developers := Developers.list,
    scalaVersion := Dependencies.scala212,
    version ~= { dynVer =>
      if (isRelease) dynVer
      else "2.0.0-SNAPSHOT" // only for local publishing
    }
    // resolvers += Resolver.mavenLocal
  )
)

lazy val root = project
  .in(file("."))
  .aggregate(core, sbtPlugin, testAgent, expressionCompiler)
  .settings(
    PgpKeys.publishSigned := {},
    publishLocal := {}
  )

lazy val core = project
  .in(file("core"))
  .enablePlugins(SbtJdiTools)
  .settings(
    name := "scala-debug-adapter",
    libraryDependencies ++= List(
      Dependencies.asm,
      Dependencies.asmUtil,
      Dependencies.javaDebug,
      Dependencies.utest % Test,
      Dependencies.sbtIo % Test,
      Dependencies.coursier % Test,
      Dependencies.coursierJvm % Test
    ),
    testFrameworks += new TestFramework("utest.runner.Framework"),
    // Test / javaOptions += "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=1044",
    Test / fork := true
  )
  .dependsOn(testClient % Test, expressionCompiler % Test)

lazy val testClient = project
  .in(file("test-client"))
  .settings(
    name := "debug-adapter-test-client",
    libraryDependencies ++= List(
      Dependencies.asm,
      Dependencies.asmUtil,
      Dependencies.javaDebug
    )
  )

lazy val sbtPlugin = project
  .in(file("sbt/plugin"))
  .enablePlugins(SbtPlugin, ContrabandPlugin, JsonCodecPlugin)
  .settings(
    name := "sbt-debug-adapter",
    sbtVersion := "1.4.9",
    scriptedSbt := "1.5.5",
    Compile / generateContrabands / contrabandFormatsForType := ContrabandConfig.getFormats,
    scriptedLaunchOpts += s"-Dplugin.version=${version.value}",
    // scriptedBufferLog := false,
    scriptedDependencies := {
      publishLocal.value
      (core / publishLocal).value
      (testClient / publishLocal).value
      (testAgent / publishLocal).value
    }
  )
  .dependsOn(core, testAgent)

// copy of https://github.com/sbt/sbt/tree/develop/testing/agent/src/main/java/sbt
lazy val testAgent = project
  .in(file("sbt/test-agent"))
  .settings(
    name := "sbt-debug-test-agent",
    autoScalaLibrary := false,
    crossPaths := false,
    libraryDependencies += Dependencies.sbtTestInterface
  )

lazy val expressionCompiler = project
  .in(file("expression-compiler"))
  .settings(
    name := "expression-compiler",
    crossScalaVersions := Seq(
      "3.0.2-RC1",
      "3.0.1",
      "3.0.0",
      "2.13.6",
      "2.13.5",
      "2.13.4",
      "2.13.3",
      "2.12.14",
      "2.12.13",
      "2.12.12",
      "2.12.11",
      "2.12.10"
    ),
    Compile / doc := {
      // Scaladoc fails
      new File("")
    },
    crossTarget := target.value / s"scala-${scalaVersion.value}",
    crossVersion := CrossVersion.full,
    libraryDependencies ++= {
      CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, _)) =>
          List("org.scala-lang" % "scala-compiler" % scalaVersion.value)
        case _ => Nil
      }
    }
  )
