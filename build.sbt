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

import ReleaseTransformations._

lazy val buildSettings: Seq[Setting[_]] = Seq(
  organization := "org.xerial.sbt",
  organizationName := "Xerial project",
  organizationHomepage := Some(new URL("http://xerial.org/")),
  description := "A sbt plugin for publishing Scala/Java projects to the Maven Central through Sonatype Nexus REST API",
  publishArtifact in Test := false,
  sbtPlugin := true,
  parallelExecution := true,
  scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked"),
  scriptedBufferLog := false,
  scriptedLaunchOpts := {
    scriptedLaunchOpts.value ++ Seq("-Xmx1024M", "-XX:MaxPermSize=256M", "-Dplugin.version=" + version.value)
  },
  crossSbtVersions := Vector("1.2.8"),
  releaseCrossBuild := false,
  releaseTagName := { (version in ThisBuild).value },
  releasePublishArtifactsAction := PgpKeys.publishSigned.value,
  releaseProcess := Seq[ReleaseStep](
    checkSnapshotDependencies,
    inquireVersions,
    runClean,
    releaseStepCommandAndRemaining("^ test"),
    setReleaseVersion,
    commitReleaseVersion,
    tagRelease,
    releaseStepCommandAndRemaining("publishSigned"),
    releaseStepCommand("sonatypeBundleRelease"),
    setNextVersion,
    commitNextVersion,
    pushChanges
  )
)

val AIRFRAME_VERSION = "20.12.1"

// Project modules
lazy val sbtSonatype =
  project
    .withId("sbt-sonatype")
    .in(file("."))
    .enablePlugins(ScriptedPlugin)
    .settings(
      buildSettings,
      testFrameworks += new TestFramework("wvlet.airspec.Framework"),
      libraryDependencies ++= Seq(
        "org.sonatype.spice.zapper" % "spice-zapper"   % "1.3",
        "org.wvlet.airframe"        %% "airframe-http" % AIRFRAME_VERSION,
        "org.wvlet.airframe"        %% "airspec"       % AIRFRAME_VERSION % "test"
      )
    )
