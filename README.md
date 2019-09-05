sbt-sonatype plugin
======

A sbt plugin for publishing your project to the Maven central repository through the REST API of Sonatype Nexus. Deploying artifacts to Sonatype repository is a requirement for synchronizing your projects to the [Maven central repository](http://repo1.maven.org/maven2/). __sbt-sonatype__ plugin enables three-step release of Scala/Java projects.

 * `sonatypePrepare`
    * Prepare a staging repository at Sonatype. It will also clean up previously created staging repositories. This step is added since sbt-sonatype 3.0 to support retyring the entire release process from scratch.
 * `publishSigned` (with [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/))
    * Upload GPG signed artifacts to Sonatype repository
 * `sonatypeRelease`
    * Perform the close and release steps in the Sonatype Nexus repository.

 After these steps, your project will be synchronized to the Maven central (usually) within ten minutes. No longer need to enter the web interface of
 [Sonatype Nexus repository](http://oss.sonatype.org/) to performe these release steps.


- [Release notes](ReleaseNotes.md)
- sbt-sonatype is available for sbt 1.x series.
- You can also use sbt-sonatype for [publishing non-sbt projects](README.md#publishing-maven-projects) (e.g., Maven, Gradle, etc.)

## Prerequisites
 
 * Create a Sonatype Repository account 
   * Follow the instruction in the [Central Repository documentation site](http://central.sonatype.org).
     * Create a Sonatype account
     * Create a GPG key
     * Open a JIRA ticket to get a permission for synchronizing your project to the Central Repository (aka Maven Central).

 * Related articles:
    * [Deploying to Sonatype - sbt Documentation](http://www.scala-sbt.org/release/docs/Community/Using-Sonatype.html)
    * [Publishing SBT projects to Nexus](http://www.cakesolutions.net/teamblogs/2012/01/28/publishing-sbt-projects-to-nexus/)
    * [Uploading to a Staging Repository via REST API](https://support.sonatype.com/hc/en-us/articles/213465868-Uploading-to-a-Staging-Repository-via-REST-API)

## Configurations

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.xerial.sbt/sbt-sonatype/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.xerial.sbt/sbt-sonatype)

### project/plugins.sbt

Import ***sbt-sonatype*** plugin and [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/) to use `sonatypeRelease` and `publishSigned`
commands:
```scala
// For sbt 1.x (sbt-sonatype 2.3 or higher)
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "(version)")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "2.0.0-M2")

// For sbt 0.13.x (upto sbt-sonatype 2.3)
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "(version)")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0")
```

 * If downloading the sbt-sonatype plugin fails, check the repository in the Maven central: <http://repo1.maven.org/maven2/org/xerial/sbt/sbt-sonatype_2.12_1.0>. It will be usually synced within 10 minutes.

### build.sbt

```scala
// [Important] Use publishTo settings set by sbt-sonatype plugin
publishTo := sonatypePublishTo.value

// [Optional] If you need to manage unique session names, change this setting:
sonatypeSessionName := s"[sbt-sonatype] ${name.value} ${version.value}",
```

### $HOME/.sbt/(sbt-version 0.13 or 1.0)/sonatype.sbt

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
sonatypeProfileName := "(your organization. e.g., org.xerial)"

// To sync with Maven central, you need to supply the following information:
publishMavenStyle := true

// License of your choice
licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

// Where is the source code hosted
import xerial.sbt.Sonatype._
sonatypeProjectHosting := Some(GitHubHosting("username", "projectName", "user@example.com"))
// or
sonatypeProjectHosting := Some(GitLabHosting("username", "projectName", "user@example.com"))

// or if you want to set these fields manually
homepage := Some(url("https://(your project url)"))
scmInfo := Some(
  ScmInfo(
    url("https://github.com/(account)/(project)"),
    "scm:git@github.com:(account)/(project).git"
  )
)
developers := List(
  Developer(id="(your id)", name="(your name)", email="(your e-mail)", url=url("(your home page)"))
)
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

## Commands

### Basic Commands
* __sonatypePrepare__
  * Drop (if exists) and create a new staging repository using `sonatypeSessionName` as a unique key
  * For cross-build projects, make sure running this command only once at the beginning of the release process. Then run `sonatypeOpen` for each build to reuse the already created stging repository.
* __sonatypeOpen__
  * Open the existing staging repository using `sonatypeSessionName` as a unique key. If it doesn't exist, create a new one. It will update`sonatypePublishTo`
  * This command is useful to run `publishSigned` task in parallel. 
* __sonatypeRelease__ (repositoryId)?
  * Close (if needed) and promote a staging repository. After this command, the uploaded artifacts will be synchronized to Maven central.

### Batch Operations
* __sonatypeDropAll__ (sonatypeProfileName)?
   * Drop all staging repositories.
* __sonatypeReleaseAll__ (sonatypeProfileName)?
  * Close and promote all staging repositories (Useful for cross-building projects)

## Others
* __sonatypeStagingProfiles__
  * Show the list of staging profiles, which include profileName information.
* __sonatypeLog__
  * Show the staging activity logs
* __sonatypeClose__
  * Close the open staging repository (= requirement verification)
* __sonatypePromote__
  * Promote the closed staging repository (= sync to Maven central)
* __sonatypeDrop__ 
  * Drop an open or closed staging repository

## Uploading Artifacts In Parallel

Since sbt-sonatype 3.x, it supports session based release flows:

### Single Module Projects
  - sonatypePrepare
  - publishSigned
  - sonatypeRelease

### Multi Module Projects
  - Run `sonatypePrepare` in a single step.
    - You must wait for the completion of this step
  - Then, start uploading signed artifacts using multiple processes:
    - P1: `; sonatypeOpen; publishSigned`
    - P2: `; sonatypeOpen; publishSigned`
    - P3: ...
  - Wait for all upload completion
  - Finally, run `sonatypeRelease`

For sbt-sonatype 2.x:
* [Example workflow for creating & publishing to a staging repository](workflow.md)

## Using with sbt-release plugin

To perform publishSigned and sonatypeReleaseAll with [sbt-release](https://github.com/sbt/sbt-release) plugin, define your custom release process as follows:

```scala
import ReleaseTransformations._

releaseCrossBuild := true // true if you cross-build the project for multiple Scala versions
releaseProcess := Seq[ReleaseStep](
  checkSnapshotDependencies,
  inquireVersions,
  runClean,
  runTest,
  setReleaseVersion,
  commitReleaseVersion,
  tagRelease,
  releaseStepCommand("sonatypePrepare"),
  // For non cross-build projects, use releaseStepCommand("publishSigned")
  releaseStepCommandAndRemaining("+publishSigned"),
  releaseStepCommand("sonatypeRelease"),
  setNextVersion,
  commitNextVersion,
  pushChanges
)
```

## Publishing Maven Projects

If your Maven project (including Gradle, etc.) is already deployed to the staging repository of Sonatype, you can use `sbt sonatypeReleaseAll (sonatypeProfileName)` command
for the synchronization to the Maven central (Since version 0.5.1).

Prepare the following two files:

### $HOME/.sbt/(sbt-version 0.13 or 1.0)/plugins/plugins.sbt

```scala
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "(version)")
```

### $HOME/.sbt/(sbt-version 0.13 or 1.0)/sonatype.sbt
```scala
credentials += Credentials("Sonatype Nexus Repository Manager",
        "oss.sonatype.org",
        "(Sonatype user name)",
        "(Sonatype password)")
```

Alternatively, the credentials can also be set with the environment variables `SONATYPE_USERNAME` and `SONATYPE_PASSWORD`.

Then, run `sonatypeReleaseAll` command by specifying your `sonatypeProfileName`. If this is `org.xerial`, run:
```
$ sbt "sonatypeReleaseAll org.xerial"
```

