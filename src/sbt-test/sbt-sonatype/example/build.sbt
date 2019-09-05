organization := "org.xerial.example"
sonatypeProfileName := "org.xerial"
publishMavenStyle := true
licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
homepage := Some(url("https://github.com/xerial/sbt-sonatype"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/xerial/sbt-sonatype"),
    "scm:git@github.com:xerial/sbt-sonatype.git"
  )
)
developers := List(
  Developer(id = "leo", name = "Taro L. Saito", email = "leo@xerial.org", url = url("http://xerial.org/leo"))
)
publishTo := sonatypePublishTo.value

