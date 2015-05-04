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

package xerial.sbt

import sbt._
import Keys._
import sbt.ScriptedPlugin._
import sbtrelease._
import ReleaseStateTransformations._
import sbtrelease.ReleasePlugin._

object SonatypeBuild extends Build {

  val SCALA_VERSION = "2.10.5"

  lazy val buildSettings = releaseSettings ++ scriptedSettings ++ Seq[Setting[_]](
    organization := "org.xerial.sbt",
    organizationName := "Xerial project",
    organizationHomepage := Some(new URL("http://xerial.org/")),
    description := "A sbt plugin for publishing Scala/Java projects to the Maven Central through Sonatype Nexus REST API",
    scalaVersion := SCALA_VERSION,
    publishArtifact in Test := false,
    sbtPlugin := true,
    parallelExecution := true,
    scalacOptions ++= Seq("-encoding", "UTF-8", "-deprecation", "-unchecked", "-target:jvm-1.6"),
    scriptedBufferLog := false,
    scriptedLaunchOpts ++= {
      import scala.collection.JavaConverters._
      val memOpt : Seq[String] = management.ManagementFactory.getRuntimeMXBean().getInputArguments().asScala.filter(a => Seq("-Xmx","-Xms").contains(a) || a.startsWith("-XX")).toSeq
      memOpt ++ Seq(s"-Dplugin.version=${version.value}")
    },
    ReleaseKeys.tagName := { (version in ThisBuild).value },
    ReleaseKeys.releaseProcess := Seq[ReleaseStep](
      checkSnapshotDependencies,
      inquireVersions,
      runClean,
      runTest,
      setReleaseVersion,
      commitReleaseVersion,
      tagRelease,
      ReleaseStep(action = Command.process("publishSigned", _)),
      setNextVersion,
      commitNextVersion,
      ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
      pushChanges
    )
  )

  // Project modules
  lazy val sbtSonatype = Project(
    id = "sbt-sonatype",
    base = file(".")
  ).settings(buildSettings: _*)
    .settings(
      libraryDependencies ++= Seq(
        "org.apache.httpcomponents" % "httpclient" % "4.2.6",
        "org.scalatest" % "scalatest_2.10" % "2.0" % "test"
      )
    )

}








