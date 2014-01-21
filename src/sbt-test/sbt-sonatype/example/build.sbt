import sbt.Keys._
import SonatypeKeys._

sonatypeSettings

organization := "org.xerial.example"

profileName := "org.xerial"

version := "0.1"

pomExtra := {
  //<url>http://xerial.org/</url>
//  <licenses>
//    <license>
//      <name>Apache 2</name>
//      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
//    </license>
//  </licenses>
  <scm>
    <connection>scm:git:github.com/xerial/sbt-sonatype.git</connection>
    <developerConnection>scm:git:git@github.com:xerial/sbt-sonatype.git</developerConnection>
    <url>github.com/xerial/sbt-sonatype.git</url>
  </scm>
}


