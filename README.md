sbt-sonatype plugin
======

A sbt plugin for publishing your project to the Maven central repository through the REST API of Sonatype Nexus. Deploying artifacts to Sonatype repository is a requirement for synchronizing your projects to the [Maven central repository](http://repo1.maven.org/maven2/). __sbt-sonatype__ plugin enables two-step release of Scala/Java projects.

 * First `publishSigned` (with [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/))
 * Next `sonatypeRelease` to perform the close and release steps in the Sonatype Nexus repository. 
 * Done. Your project will be synchronized to the Maven central within tens of minutes. No longer need to enter the web interface of
 [Sonatype Nexus repository](http://oss.sonatype.org/).

- [Release notes](ReleaseNotes.md)
- sbt-sonatype is available for sbt-0.13.5 or later. 
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

### project/plugins.sbt

Import ***sbt-sonatype*** plugin and [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/) to use `sonatypeRelease` and `publishSigned`
commands:
```scala
// For sbt 0.13.x
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "1.1")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0")

// For sbt 1.0.0-RC3, 1.0.0-M6, 1.0.0-M5, and 0.13.x
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.0") 
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.0-M1")
```

 * If downloading the sbt-sonatype plugin fails, check the repository in the Maven central: <http://repo1.maven.org/maven2/org/xerial/sbt/sbt-sonatype_2.10_0.13>. It will be synchronized every ~2 hours.

### build.sbt

```scala
// Add sonatype repository settings
publishTo := Some(
  if (isSnapshot.value)
    Opts.resolver.sonatypeSnapshots
  else
    Opts.resolver.sonatypeStaging
)
```

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
sonatypeProfileName := "(your organization. e.g., org.xerial)"

// To sync with Maven central, you need to supply the following information:
publishMavenStyle := true

// License of your choice
licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))
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

## Available Commands

* __sonatypeList__
  * Show the list of staging repositories.
* __sonatypeOpen__ (description | sonatypeProfileName description)   (since sbt-sonatype-1.1)
  * Create a staging repository and set `sonatypeStagingRepositoryProfile` and `publishTo`.
  * Although creating a staging repository does not result in email notifications,
    the description will be reused for across lifecycle operations (Close, Promote, Drop)
    to facilitate distinguishing email notifications sent by the repository by description.
* __sonatypeClose__ (repositoryId)?
  * Close an open staging repository and set `sonatypeStagingRepositoryProfile` and
    clear `publishTo` if it was set by __sonatypeOpen__.
  * The `Staging Completed` email notification sent by the repository only includes the description
    (if created with __sonatypeOpen__); it does not include the staging repository ID.
* __sonatypePromote__ (repositoryId)?
  * Promote a closed staging repository and set `sonatypeStagingRepositoryProfile` and
    clear `publishTo` if it was set by __sonatypeOpen__.
  * The `Promotion Completed` email notification sent by the repository only includes the description
    (if created with __sonatypeOpen__); it does not include the staging repository ID.
* __sonatypeDrop__ (repositoryId)?
  * Drop an open or closed staging repository and set `sonatypeStagingRepositoryProfile` and
    clear `publishTo` if it was set by __sonatypeOpen__.
  * The email notification sent by the repository includes both the description
    (if created with __sonatypeOpen__) and the staging repository ID.
* __sonatypeRelease__ (repositoryId)?
  * Close (if needed) and promote a staging repository and set `sonatypeStagingRepositoryProfile` and
    clear `publishTo` if it was set by __sonatypeOpen__.
  * The email notifications are those of __sonatypeClose__ (if applicable) and __sonatypePromote__.
* __sonatypeReleaseAll__ (sonatypeProfileName)?
  * Close and promote all staging repositories (Useful for cross-building projects)
* __sonatypeStagingProfiles__
  * Show the list of staging profiles, which include profileName information.
* __sonatypeLog__
  * Show the staging activity logs

### Advanced Usage

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
  ReleaseStep(action = Command.process("publishSigned", _)),
  setNextVersion,
  commitNextVersion,
  ReleaseStep(action = Command.process("sonatypeReleaseAll", _)),
  pushChanges
)

## Publishing Maven Projects

If your Maven project (including Gradle, etc.) is already deployed to the staging repository of Sonatype, you can use `sbt sonatypeReleaseAll (sonatypeProfileName)` command
for the synchronization to the Maven central (Since version 0.5.1).

Prepare the following two files:

### $HOME/.sbt/(sbt-version 0.13 or 1.0)/plugins/plugins.sbt

```scala
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.0")
```

### $HOME/.sbt/(sbt-version 0.13 or 1.0)/sonatype.sbt
```scala
credentials += Credentials("Sonatype Nexus Repository Manager",
        "oss.sonatype.org",
        "(Sonatype user name)",
        "(Sonatype password)")
```

Then, run `sonatypeReleaseAll` command by specifying your `sonatypeProfileName`. If this is `org.xerial`, run:
```
$ sbt "sonatypeReleaseAll org.xerial"
```

