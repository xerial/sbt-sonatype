sbt-sonatype plugin
---

A sbt plugin for automating release processes at Sonatype Nexus, that is required to synchronize your project jars to the [Maven central repository](http://repo1.maven.org/maven2).


## Prerequisites
 
 * Create a Sonatype Repository account 
   * Follow the instruction in [Sonatype OSS Maven Repository Usage Guide](https://docs.sonatype.org/display/Repository/Sonatype+OSS+Maven+Repository+Usage+Guide). At least you need to create a GPG key, and open a JIRA ticket to get a permission for publishing your project to Maven central.

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

Add [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/) to use `publish-signed` command:
`$HOME/.sbt/(sbt-vesrion)/plugins/build.sbt`

```scala
addSbtPlugin("com.typesafe.sbt" % "sbt-pgp" % "0.8.1")
```


`build.sbt`

```scala
import SonatypeKeys._

// Import default settings. This line changes `publishTo` to use Sonatype repository.
sonatypeSettings
 // Your project orgnization (package name)
organization := "org.xerial.example" 
 // Your profile name of the sonatype account. The default is the same with the organization 
profileName := "org.xerial" 

// Project version. Only release version (w/o SNAPSHOT suffix) can be promoted.
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

## Publish your artifact

The general step to publish your artifact to maven central is: 

 * `publish-signed` to deploy your artifact to staging repository at Sonatype.
 * `close` your staging repository at Sonatype. This step verifiles Maven central sync requiement, including GPG signature, pom.xml settings, etc.
 * `promote` the closed repository so that it can be synched with Maven central. 

First you need to set a release version (that is a version without SNAPSHOT suffix) in your project settings. Otherwise your project will be published to the [snapshot repository](http://oss.sonatype.org/content/repositories/snapshots) of Sonatype and cannot be promoted.

Publish a GPG-signed artifact to Sonatype:
```
$ sbt publish-signed
```

Do close and promote at once:
```
$ sbt closeAndPromote
```
This commands accesses [Sonatype Nexus REST API](https://oss.sonatype.org/nexus-staging-plugin/default/docs/index.html), then send close and promote commands. 



## Available Commands

* **list**: List staging repositories 
* **close** (repositoryId)?: Close a staging repository
* **promote** (repositoyrId)?: Promote a staging repository
* **closeAndPromote** (repositoryId)?: Close and promote a stagint repository.
