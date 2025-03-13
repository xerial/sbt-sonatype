/*
 * Copyright 2012 Taro L. Saito
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

crossScalaVersions += "3.6.2"

pluginCrossBuild / sbtVersion := {
  scalaBinaryVersion.value match {
    case "2.12" =>
      (pluginCrossBuild / sbtVersion).value
    case _ =>
      "2.0.0-M4"
  }
}

addCommandAlias("format", "scalafmtAll; scalafmtSbt")

Global / onChangedBuildSource := ReloadOnSourceChanges

val versions = new {
  val scala                = "2.12.20" // Must use Scala 2.12.x for sbt plugins
  val airframe             = "24.12.2"
  val sonatypeZapperClient = "1.3"
  val sttp                 = "4.0.0-M16"
  val sonatypeClient       = "0.3.0"
}

ThisBuild / dynverSeparator := "-"

// Set scala version for passing scala-steward run on JDK20
ThisBuild / scalaVersion := versions.scala

lazy val buildSettings: Seq[Setting[_]] = Seq(
  organization         := "org.xerial.sbt",
  organizationName     := "Xerial project",
  organizationHomepage := Some(url("http://xerial.org/")),
  description := "A sbt plugin for publishing Scala/Java projects to the Maven Central through Sonatype Nexus REST API",
  Test / publishArtifact := false,
  sbtPlugin              := true,
  parallelExecution      := true,
  // Enforcing JDK8 target
  javacOptions ++= Seq("-source", "8", "-target", "8"),
  scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked"),
  scriptedBufferLog := false,
  scriptedLaunchOpts := {
    scriptedLaunchOpts.value ++ Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
  }
)

val AIRFRAME_VERSION = "24.6.1"

// Project modules
lazy val sbtSonatype =
  project
    .withId("sbt-sonatype")
    .in(file("."))
    .enablePlugins(ScriptedPlugin, BuildInfoPlugin)
    .settings(
      buildSettings,
      testFrameworks += new TestFramework("wvlet.airspec.Framework"),
      buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion, sbtVersion),
      buildInfoPackage := "org.xerial.sbt.sonatype",
      scalacOptions += {
        scalaBinaryVersion.value match {
          case "2.12" =>
            "-Ywarn-unused-import"
          case _ =>
            "-Wunused:imports"
        }
      },
      libraryDependencies ++= Seq(
        "org.sonatype.spice.zapper" % "spice-zapper"  % versions.sonatypeZapperClient,
        "org.wvlet.airframe"       %% "airframe-http" % versions.airframe
        // A workaround for sbt-pgp, which still depends on scala-parser-combinator 1.x
        excludeAll (ExclusionRule("org.scala-lang.modules", "scala-parser-combinators_2.12")),
        "org.wvlet.airframe"            %% "airspec"                           % versions.airframe % Test,
        "com.lumidion"                  %% "sonatype-central-client-sttp-core" % versions.sonatypeClient,
        "com.lumidion"                  %% "sonatype-central-client-upickle"   % versions.sonatypeClient,
        "com.softwaremill.sttp.client4" %% "slf4j-backend"                     % versions.sttp,
        "com.softwaremill.sttp.client4" %% "upickle"                           % versions.sttp
      )
    )
