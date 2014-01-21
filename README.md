sbt-sonatype plugin
---

A sbt plugin for automating release processes at Sonatype Nexus.


## Prerequisite
 
 * Create a Sonatype Repository account
   * Follow the instruction in [Sonatype OSS Maven Repository Usage Guide](https://docs.sonatype.org/display/Repository/Sonatype+OSS+Maven+Repository+Usage+Guide).


## Usage

Add sbt-sonatype plugin to your project settings:
`project/plugins.sbt`
```scala
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "0.1.0")
```

Set Sonatype account information (user name and password) in the global setting file. Never include this settings to your project. 
`$HOME/.sbt/(sbt-version)/sonatype.sbt`

```scala
credentials += Credentials("Sonatype Nexus Repository Manager",
	    "oss.sonatype.org",
	    "(Sonatype user name)",
	    "(Sonatype password)"
```

Use [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/) to use `publish-signed` command:
`$HOME/.sbt/(sbt-vesrion)/plugins/build.sbt`

```scala
addSbtPlugin("com.typesafe.sbt" % "sbt-pgp" % "0.8.1")
```



`build.sbt`

```scala
import SonatypeKeys._

sonatypeSettings
 // Your project orgnization
organization := "org.xerial.example" 
 // Your profile name of the sonatype account. The default is the same with the organization 
profileName := "org.xerial" 

// Project version
version := "0.1" 

// To sync with Maven central, you need to supply the following information:
pomExtra := {
  <url>(your project URL)</url>
  <!-- License of your choice. -->
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <!-- SCM information. Modify -->
  <scm>
    <connection>scm:git:github.com/xerial/sbt-sonatype.git</connection>
    <developerConnection>scm:git:git@github.com:xerial/sbt-sonatype.git</developerConnection>
    <url>github.com/xerial/sbt-sonatype.git</url>
  </scm>
  <!-- Developer contact information -->
  <developers>
    <developer>
      <id>leo</id>
      <name>Taro L. Saito</name>
      <url>http://xerial.org/leo</url>
    </developer>
  </developers>
}
```

