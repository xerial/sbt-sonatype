sbt-sonatype plugin
======

A sbt plugin for publishing your project to the Maven central repository through the REST API of Sonatype Nexus. Deploying artifacts to Sonatype repository is a requirement for synchronizing your projects to the [Maven central repository](http://repo1.maven.org/maven2/). __sbt-sonatype__ plugin enables two-step release of Scala/Java projects.

 * First `publishSigned` (with [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/))
 * Next `sonatypeRelease` to perform the close and release steps in the Sonatype Nexus repository. 
 * Done. Your project will be synchronized to the Maven central within tens of minutes. No longer need to enter the web interface of
 [Sonatype Nexus repository](http://oss.sonatype.org/).

- [Release notes](ReleaseNotes.md)
- sbt-sonatype is available for sbt-0.13.5 or later. 
- You can also use sbt-sonatype for [publishing Java projects](#publishing-maven-projects).


## Prerequisites
 
 * Create a Sonatype Repository account 
   * Follow the instruction in the [Central Repository documentation site](http://central.sonatype.org). 
     * Create a Sonatype account
     * Create a GPG key
     * Open a JIRA ticket to get a permission for synchronizing your project to the Central Repository (aka Maven Central).

 * Related articles:
    * [Deploying to Sonatype - sbt Documentation](http://www.scala-sbt.org/release/docs/Community/Using-Sonatype.html)
    * [Publishing SBT projects to Nexus](http://www.cakesolutions.net/teamblogs/2012/01/28/publishing-sbt-projects-to-nexus/)

## Configurations

### project/plugins.sbt

Import ***sbt-sonatype*** plugin and [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/) to use `sonatypeRelease` and `publishSigned`
commands:
```scala
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "0.5.0")

addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0") // fot sbt-0.13.5 or higher
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

### (project root)/sonatype.sbt

sbt-sonatype is an auto-plugin, it will automatically configure your build. There are a few settings though that you need to define yourself:

  * `sonatypeProfileName` 
     * This is your Sonatype acount profile name, e.g. `org.xerial`. If you do not set this value, it will be the same with the `organization` value.
  * `pomExtra`
     * A fragment of Maven's pom.xml. You must define url, licenses, scm and developers tags in this XML to satisfy [Central Repository sync requirements](http://central.sonatype.org/pages/requirements.html).
  

```scala
// Your profile name of the sonatype account. The default is the same with the organization value
sonatypeProfileName := "org.xerial"

// To sync with Maven central, you need to supply the following information:
pomExtra in Global := {
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
 * `sonatypeRelease` do `sonatypeClose` and `sonatypePromote` in one step.
   * `sonatypeClose` closes your staging repository at Sonatype. This step verifies Maven central sync requirement, GPG-signature, javadoc
   and source code presence, pom.xml settings, etc.
   * `sonatypePromote` command verifies the closed repository so that it can be synchronized with Maven central.


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
* __sonatypeReleaseAll__ (sonatypeProfileName)?
  * Close and promote all staging repositories (Useful for cross-building projects)
* __sonatypeStagingProfiles__
  * Show the list of staging profiles, which include profileName information.
* __sonatypeLog__
  * Show the staging activity logs

## Using with sbt-release plugin

To perform publishSigned and sonatypeReleaseAll with [sbt-release](https://github.com/sbt/sbt-release) plugin, define your custom release process as follows:

```scala
import ReleaseTransformations._

releaseProcess := Seq[ReleaseStep](
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

```
To enable cross building, set `enableCrossBuild = true` in publishSigned and sonatypeReleaseAll release steps:
```
  ReleaseStep(action = Command.process("publishSigned", _), enableCrossBuild = true)
  ...
  ReleaseStep(action = Command.process("sonatypeReleaseAll", _), enableCrossBuild = true)
```

## Publishing Maven Projects

If your Maven project is already deployed to the staging repository of Sonatype, you can use `sbt sonatypeReleaseAll (sonatypeProfileName)` command
for the synchronization to the Maven central (Since version 0.5.1).

Prepare the following two files:

### $HOME/.sbt/0.13/plugins/plugins.sbt

```scala
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "0.5.1")
```

### $HOME/.sbt/0.13/sonatype.sbt
```scala
credentials += Credentials("Sonatype Nexus Repository Manager",
	    "oss.sonatype.org",
	    "(Sonatype user name)",
	    "(Sonatype password)")
```

Then, run `sonatypeReleaseAll` command by specifying your `sonatypeProfileName`:
```
$ sbt sonatypeReleaseAll org.xerial
```

