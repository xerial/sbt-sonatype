sbt-sonatype plugin
======

A sbt plugin for publishing your project to the Maven central repository through the REST API of Sonatype Nexus. Deploying artifacts to Sonatype repository is a requiremnt for synchronizing your projects to the [Maven central repository](http://repo1.maven.org/maven2/). __sbt-sonatype__ plugin enables two-step release of Scala/Java projects.

 * First `publishSigned` (with [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/))
 * Next `sonatypeRelease` to perform the close and release steps in the Sonatype Nexus repository. 
 * Done. Your project will be synchoronized to the Maven central in a few hours. No longer need to enter the web interface of [Sonatype Nexus repository](http://oss.sonatype.org/).

## Prerequisites
 
 * Create a Sonatype Repository account 
   * Follow the instruction in the [Central Repository documentation site](http://central.sonatype.org). 
     * Create a Sonatype account
     * Create a GPG key
     * Open a JIRA ticket to get a permission for synchronizing your project to the Central Repository (aka Maven Central).

 * Related articles:
    * [Deploying to Sonatype - sbt Documentation](http://www.scala-sbt.org/release/docs/Community/Using-Sonatype.html)
    * [Publishing SBT projects to Nexus](http://www.cakesolutions.net/teamblogs/2012/01/28/publishing-sbt-projects-to-nexus/)

## Usage

sbt-sonatype is available for sbt-0.13.x.

### project/plugins.sbt

Import ***sbt-sonatype*** plugin and [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/) to use `sonatypeRelease` and `publish-signed` commands.
```scala
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "0.3.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-pgp" % "0.8.3")
```

 * If downloading the sbt-sonatype plugin fails, check the repository in the Maven central: <http://repo1.maven.org/maven2/org/xerial/sbt/sbt-sonatype_2.10_0.13>. It will be synchronized every ~2 hours.

### $HOME/.sbt/(sbt-version)/sonatype.sbt

Set Sonatype account information (user name and password) in the global sbt settings. To protect your password, never include this file within your project.

```scala
credentials += Credentials("Sonatype Nexus Repository Manager",
	    "oss.sonatype.org",
	    "(Sonatype user name)",
	    "(Sonatype password)")
```

### build.sbt

sbt-sonatype is an autoplugin, it will automatically configure your build.  There are a few settings though that you need to define yourself.  Import `SonatypeKeys._`, and then define the following keys:

  * `sonatypeProfileName` 
     * This is your Sonatype acount profile name, e.g. `org.xerial`. If you do not set this value, it will be the same with the `organization` value.
  * `pomExtra`
     * A fragment of Maven's pom.xml. You must define url, licenses, scm and deverlopers tags in this XML to satisfy [Central Repository sync requirements](http://central.sonatype.org/pages/requirements.html).
  

```scala
import SonatypeKeys._

// Your project orgnization (package name)
organization := "org.xerial.example" 

// Your profile name of the sonatype account. The default is the same with the organization 
sonatypeProfileName := "org.xerial" 

// Project version. Only release version (w/o SNAPSHOT suffix) can be promoted.
version := "0.1" 

// To sync with Maven central, you need to supply the following information:
pomExtra := {
  <url>(your project URL)</url>
  <!-- License of your choice -->
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <!-- SCM information. Modify the follwing URLs -->
  <scm>
    <connection>scm:git:github.com/(your repository URL)</connection>
    <developerConnection>scm:git:git@github.com:(your repository URL)</developerConnection>
    <url>github.com/(your repository url)</url>
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

The general steps for publishing your artifact to the Central Repository are as follows: 

 * `publishSigned` to deploy your artifact to staging repository at Sonatype.
 * `sonatypeClose` closes your staging repository at Sonatype. This step verifiles Maven central sync requiement, GPG-signature, javadoc and source code presence, pom.xml settings, etc.
 * `sonatypePromote` command verifies the closed repository so that it can be synchronized with Maven central. 
   * `sonatypeRelease` will do both `sonatypeClose` and `sonatypePromote` in one step.

Note: If your project version has "SNAPSHOT" suffix, your project will be published to the [snapshot repository](http://oss.sonatype.org/content/repositories/snapshots) of Sonatype, and you cannot use `sonatypeRelease` command. 

### Command Line Usage

Publish a GPG-signed artifact to Sonatype:
```
$ sbt publishSigned
```

Do close and promote at once:
```
$ sbt sonatypeRelease
```
This command accesses [Sonatype Nexus REST API](https://oss.sonatype.org/nexus-staging-plugin/default/docs/index.html), then sends close and promote commands. 


## Available Commands

* __sonatypeList__
  * Show the list of staging repositories.
* __sonatypeClose__ (repositoryId)?
  * Close a staging repository.
* __sonatypePromote__ (repositoryId)?
  * Promote a staging repository.
* __sonatypeDrop__ (repositoryId)?
  * Drop a staging repository.
* __sonatypeRelease__ (repositoryId)?
  * Close and promote a staging repository.
* __sonatypeReleaseAll__
  * Close and promote all staging repositories (Useful for cross-building projects)
* __sonatypeStagingProfiles__
  * Show the list of staging profiles, which include profileName information.
* __sonatypeLog__
  * Show the staging activity logs

