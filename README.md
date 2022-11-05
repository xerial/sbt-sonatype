sbt-sonatype plugin
======

A sbt plugin for publishing your project to the Maven central repository through the REST API of Sonatype Nexus. Deploying artifacts to Sonatype repository is a requirement for synchronizing your projects to the [Maven central repository](https://repo1.maven.org/maven2/). __sbt-sonatype__ plugin enables two-step release of your Scala/Java projects.

 * `publishSigned` (with [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/))
    * Create GPG signed artifacts to a local staging repository.
    * Make sure adding `publishTo := sonatypePublishToBundle.value` to your build.sbt
 * `sonatypeBundleRelease` (New in sbt-sonatype 3.4)
    * This command will prepare a new remote staging repository at Sonatype. If there are exisiting staging repositories that have the same description with `sonatypeSessionName` key, sbt-sonatype will discard them properly.
    * Then, it will upload the artifacts in the local staging folder to the remote staging repository. Uploading artifacts as a bundle is much faster than uploading each artifact to Sonatype. For example, thousands of files can be uploaded in several minutes with bundle upload.
    * Finally, this command will perform the close and release steps at the Sonatype Nexus repository to verify the Maven central requirements.

 After these steps, your project will be synchronized to the Maven central within ten minutes. No longer need to enter the web interface of
 [Sonatype Nexus repository](http://oss.sonatype.org/) to performe these release steps.


- [Release notes](ReleaseNotes.md)
- sbt-sonatype is available for sbt 1.x series.
- You can also use sbt-sonatype for [publishing non-sbt projects](README.md#publishing-maven-projects) (e.g., Maven, Gradle, etc.)
- Blog: [Blazingly Fast Release to Sonatype](https://medium.com/@taroleo/sbt-sonatype-f02bdafd78f1)

## Prerequisites

 * Create a Sonatype Repository account
   * Follow the instruction in the [Central Repository documentation site](http://central.sonatype.org).
     * Create a Sonatype account
     * Create a GPG key
     * Open a JIRA ticket to get a permission for synchronizing your project to the Central Repository (aka Maven Central).


 * Related articles:
    * [Deploying to Sonatype - sbt Documentation](http://www.scala-sbt.org/release/docs/Community/Using-Sonatype.html)
    * [Uploading to a Staging Repository via REST API](https://support.sonatype.com/hc/en-us/articles/213465868-Uploading-to-a-Staging-Repository-via-REST-API)

## Configurations

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.xerial.sbt/sbt-sonatype/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.xerial.sbt/sbt-sonatype)

### project/plugins.sbt

Import ***sbt-sonatype*** plugin and [sbt-pgp plugin](http://www.scala-sbt.org/sbt-pgp/) to use `sonatypeBundleRelease` and `publishSigned`
commands:
```scala
// For sbt 1.x (sbt-sonatype 2.3 or higher)
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "(version)")
addSbtPlugin("com.github.sbt" % "sbt-pgp" % "2.1.2")

// For sbt 0.13.x (upto sbt-sonatype 2.3)
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "(version)")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.0.0")
```

 * If downloading the sbt-sonatype plugin fails, check the repository in the Maven central: <https://repo1.maven.org/maven2/org/xerial/sbt/sbt-sonatype_2.12_1.0>. It will be usually synced within 10 minutes.

### build.sbt

To use sbt-sonatype, you need to create a bundle of your project artifacts (e.g., .jar, .javadoc, .asc files, etc.) into a local folder specified by `sonatypeBundleDirectory`. By default, the folder is `(project root)/target/sonatype-staging/(version)`. Add the following `publishTo` setting to create a local bundle of your project:
```scala
publishTo := sonatypePublishToBundle.value
```

  > ⚠️ Legacy Host
  >
  > By default, this plugin is configured to use the legacy Sonatype repository `oss.sonatype.org`. If you created a new account on or after February 2021, add `sonatypeCredentialHost` settings:
  >
  > ```scala
  > // For all Sonatype accounts created on or after February 2021
  > ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"
  > ```

With this setting, `publishSigned` will create a bundle of your project to the local staging folder. If the project has multiple modules, all of the artifacts will be assembled into the same folder to create a single bundle.

If `isSnapshot.value` is true (e.g., if the version name contains -SNAPSHOT), publishSigned task will upload files to the Sonatype Snapshots repository without using the local bundle folder.

If necessary, you can tweak several configurations:
```scala
// [Optional] The local staging folder name:
sonatypeBundleDirectory := (ThisBuild / baseDirectory).value / target.value.getName / "sonatype-staging" / (ThisBuild / version).value

// [Optional] If you need to manage unique session names by yourself, change this default setting:
sonatypeSessionName := s"[sbt-sonatype] ${name.value} ${version.value}"

// [Optional] Timeout until giving up sonatype close/promote stages. Default is 60 min.
sonatypeTimeoutMillis := 60 * 60 * 1000 

// [If you cannot use bundle upload] Use this setting when you need to uploads artifacts directly to Sonatype
// With this setting, you cannot use sonatypeBundleXXX commands
publishTo := sonatypePublishTo.value

// [If necessary] Settings for using custom Nexus repositories:
sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository := "https://s01.oss.sonatype.org/service/local"
```

### $HOME/.sbt/(sbt-version 0.13 or 1.0)/sonatype.sbt

For the authentication to Sonatype API, you need to set your Sonatype account information (user name and password) in the global sbt settings. To protect your password, never include this file within your project.

```scala
credentials += Credentials("Sonatype Nexus Repository Manager",
        "oss.sonatype.org",
        "(Sonatype user name)",
        "(Sonatype password)")
```

### (project root)/sonatype.sbt

sbt-sonatype is an auto-plugin, which will automatically configure your build. There are a few settings though that you need to define by yourself:

  * `sonatypeProfileName`
     * This is your Sonatype acount profile name, e.g. `org.xerial`. If you do not set this value, it will be the same with the `organization` value.
  * `pomExtra`
     * A fragment of Maven's pom.xml. You must define url, licenses, scm and developers tags in this XML to satisfy [Central Repository sync requirements](http://central.sonatype.org/pages/requirements.html).

Example settings:
```scala
// Your profile name of the sonatype account. The default is the same with the organization value
sonatypeProfileName := "(your organization. e.g., org.xerial)"

// To sync with Maven central, you need to supply the following information:
publishMavenStyle := true

// Open-source license of your choice
licenses := Seq("APL2" -> url("http://www.apache.org/licenses/LICENSE-2.0.txt"))

// Where is the source code hosted: GitHub or GitLab?
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

The basic steps for publishing your artifact to the Central Repository are as follows:

  * `publishSigned` to deploy your artifact to a local staging repository.
  * `sonatypeBundleRelease` (since sbt-sonatype 3.4)
    * This command is equivalent to running `; sonatypePrepare; sonatypeBundleUpload; sonatypeRelease`.
    * Internally `sonatypeRelease` will do `sonatypeClose` and `sonatypePromote` in one step.
      * `sonatypeClose` closes your staging repository at Sonatype. This step verifies Maven central sync requirement, GPG-signature, javadoc
   and source code presence, pom.xml settings, etc.
      * `sonatypePromote` command verifies the closed repository so that it can be synchronized with Maven central.

Note: If your project version has "SNAPSHOT" suffix, your project will be published to the [snapshot repository](http://oss.sonatype.org/content/repositories/snapshots) of Sonatype, and you cannot use `sonatypeBundleUpload` or `sonatypeRelease` command.

## Commands

Usually, we only need to run `sonatypeBundleRelease` command in sbt-sonatype:
* __sonatypeBundleRelease__
  * This will run a sequence of commands `; sonatypePrepare; sonatypeBundleUpload; sonatypeRelease` in one step.
  * You must run `publishSigned` before this command to create a local staging bundle.

### Individual Step Commands
* __sonatypePrepare__
  * Drop the exising staging repositories (if exist) and create a new staging repository using `sonatypeSessionName` as a unique key.
  * This will update `sonatypePublishTo` setting.
  * For cross-build projects, make sure running this command only once at the beginning of the release process.
    * Usually using sonatypeBundleUpload should be sufficient, but if you need to parallelize artifact uploads, run `sonatypeOpen` before each upload to reuse the already created stging repository.
* __sonatypeBundleUpload__
  * Upload your local staging folder contents to a remote Sonatype repository.
* __sonatypeOpen__
  * This command is necessary only when you need to parallelize `publishSigned` task. For small/medium-size projects, using only `sonatypePrepare` would work.
  * This opens the existing staging repository using `sonatypeSessionName` as a unique key. If it doesn't exist, create a new one. It will update`sonatypePublishTo`
* __sonatypeRelease__ (repositoryId)?
  * Close (if needed) and promote a staging repository. After this command, the uploaded artifacts will be synchronized to Maven central.

### Batch Operations
* __sonatypeDropAll__ (sonatypeProfileName)?
   * Drop all staging repositories.
* __sonatypeReleaseAll__ (sonatypeProfileName)?
  * Close and promote all staging repositories (Useful for cross-building projects)

## Other Commmands
* __sonatypeBundleClean__
  * Clean a local bundle folder
* __sonatypeClean__
  * Clean a remote staging repository which has `sonatypeSessionName` key.
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

## Advanced Build Settings

### Sequential Upload Release (Use this for small projects)

```scala
> ; publishSigned; sonatypeBundleRelease
```

For cross-building projects, use `+ publishSigned`:
```scala
> ; + publishSigned; sonatypeBundleRelease
```
### Parallelizing Builds When Sharing A Working Folder

When you are sharing a working folder, you can parallelize publishSigned step for each module or for each Scala binary version:

- Run multiple publishSigned tasks in parallel
- Finally, run `sonatypeBundleRelease`

### Parallelizing Builds When Not Sharing Any Working Folder

If you are not sharing any working directory (e.g., Travis CI), to parallelize the release process, you need to publish a bundle for each build because Sonatype API only supports uploading one bundle per a staging repository.

Here is an example to parallelize your build for each Scala binary version:
  - Set `sonatypeSessionName := "[sbt-sonatype] ${name.value}-${scalaBinaryVersion.value}-${version.value}"` to use unique session keys for individual Scala binary versions.
  - For each Scala version, run: `sbt ++(Scala version) "; publishSigned; sonatypeBundleRelease"`

For sbt-sonatype 2.x:
* [Example workflow for creating & publishing to a staging repository](workflow.md)

## Using with sbt-release plugin

To perform publishSigned and sonatypeBundleRelease with [sbt-release](https://github.com/sbt/sbt-release) plugin, define your custom release process as follows:

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
  // For non cross-build projects, use releaseStepCommand("publishSigned")
  releaseStepCommandAndRemaining("+publishSigned"),
  releaseStepCommand("sonatypeBundleRelease"),
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



## For sbt-sonatype developers

Releasing sbt-sonatype to Sonatype:

````
## Add a new git tag
$ git tag v3.9.x
$ ./sbt
> publishSigned
> sonatypeBundleRelease
```
