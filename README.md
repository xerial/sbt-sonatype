sbt-sonatype plugin
======

A sbt plugin for automating release processes at Sonatype Nexus. This plugin enables two-step release of your Scala/Java projects:

 * First, `publish-signed` (with [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/))
 * Next, `release-sonatype` to perform the close and release steps in Sonatype Nexus repository. 
 * That's all. Your project will be synchoronized to Maven central in a few hours. No need to enter the web interface of [Sonatype Nexus repository](http://oss.sonatype.org/).


Deploying to Sonatype repository is required for synchronizing your projects to the [Maven central repository](http://repo1.maven.org/maven2/).

## Prerequisites
 
 * Create a Sonatype Repository account 
   * Follow the instruction in [Sonatype OSS Maven Repository Usage Guide](https://docs.sonatype.org/display/Repository/Sonatype+OSS+Maven+Repository+Usage+Guide). 
     * Create a Sonatype account
     * Create a GPG key
     * Open a JIRA ticket to get a permission for synchronizing your project to Maven central.
   * Related articles:   
     * [Publishing SBT projects to Nexus](http://www.cakesolutions.net/teamblogs/2012/01/28/publishing-sbt-projects-to-nexus/) 
     * [Deploying to Sonatype - sbt Documentation](http://www.scala-sbt.org/release/docs/Community/Using-Sonatype.html
)

## Usage

### project/plugins.sbt

Import ***sbt-sonatype*** plugin to your project.
```scala
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "0.1.1")
```

 * Here is the plugin repository at Maven central: <http://repo1.maven.org/maven2/org/xerial/sbt/>


### $HOME/.sbt/(sbt-version)/sonatype.sbt

Set Sonatype account information (user name and password) in the global sbt settings. Never include this setting file to your project. 

```scala
credentials += Credentials("Sonatype Nexus Repository Manager",
	    "oss.sonatype.org",
	    "(Sonatype user name)",
	    "(Sonatype password)"
```

### $HOME/.sbt/(sbt-vesrion)/plugins/gpg.sbt

Add [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/) in order to use `publish-signed` command.

```scala
addSbtPlugin("com.typesafe.sbt" % "sbt-pgp" % "0.8.1")
```

### build.sbt

Import `xerial.sbt.Sonatype.sonatypeSettings` and `SonatypeKeys._`. Then set `profileName` (your Sonatype acount profile name. e.g. `org.xerial`) and `pomExtra`. 
At least you need to set url, licenses, scm and deverlopers information in the XML.

```scala
import SonatypeKeys._

// Import default settings. This changes publishTo settings to use the Sonatype repository and add several commands for publishing.
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
  <!-- SCM information. Modify the follwing settings: -->
  <scm>
    <connection>scm:git:github.com/xerial/sbt-sonatype.git</connection>
    <developerConnection>scm:git:git@github.com:xerial/sbt-sonatype.git</developerConnection>
    <url>github.com/xerial/sbt-sonatype.git</url>
  </scm>
  <!-- Developer contact information -->
  <developers>
    <developer>
      <id>(your favorite id)</id>
      <name>(your name)</name>
      <url>(your web page)</url>
    </developer>
  </developers>
}
```

## Publishing Your Artifact

The general steps for publishing your artifact to Maven Central are as follows: 

 * `publish-signed` to deploy your artifact to staging repository at Sonatype.
 * `close` your staging repository at Sonatype. This step verifiles Maven central sync requiement, including GPG signature, pom.xml settings, etc.
 * `promote` the closed repository so that it can be synched with Maven central. 
   * `release-sonatype` will do both `close` and `promote` in one step.

Note: If your project version has "SNAPSHOT" suffix, your project will be published to the [snapshot repository](http://oss.sonatype.org/content/repositories/snapshots) of Sonatype, and you cannot use `release-sonatype` command. 

### Command Line Usage

Publish a GPG-signed artifact to Sonatype:
```
$ sbt publish-signed
```

Do close and promote at once:
```
$ sbt release-sonatype
```
This command accesses [Sonatype Nexus REST API](https://oss.sonatype.org/nexus-staging-plugin/default/docs/index.html), then sends close and promote commands. 


## Available Commands

* __list__
  * Show the list of staging repositories.
* __close__ (repositoryId)?
  * Close a staging repository.
* __promote__ (repositoryId)?
  * Promote a staging repository.
* __release-sonatype__ (repositoryId)?
  * Close and promote a staging repository.
* __stagingProfiles__
  * Show the list of staging profiles, which include profileName information.
* __stagingActivities__
  * Show the staging activity logs

