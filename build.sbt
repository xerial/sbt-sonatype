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

Global / onChangedBuildSource := ReloadOnSourceChanges

ThisBuild / dynverSeparator := "-"

lazy val buildSettings: Seq[Setting[_]] = Seq(
  organization         := "org.xerial.sbt",
  organizationName     := "Xerial project",
  organizationHomepage := Some(new URL("http://xerial.org/")),
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

val AIRFRAME_VERSION = "23.4.2"

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
      libraryDependencies ++= Seq(
        "org.sonatype.spice.zapper" % "spice-zapper"  % "1.3",
        "org.wvlet.airframe"       %% "airframe-http" % AIRFRAME_VERSION
        // A workaround for sbt-pgp, which still depends on scala-parser-combinator 1.x
          excludeAll (ExclusionRule("org.scala-lang.modules", "scala-parser-combinators_2.12")),
        "org.wvlet.airframe" %% "airspec" % AIRFRAME_VERSION % Test
      )
    )
