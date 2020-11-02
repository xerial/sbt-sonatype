//--------------------------------------
//
// Sonatype.scala
// Since: 2014/01/05
//
//--------------------------------------

package xerial.sbt

import sbt.Keys._
import sbt._
import wvlet.log.{LogLevel, LogSupport}
import xerial.sbt.sonatype.SonatypeClient.StagingRepositoryProfile
import xerial.sbt.sonatype.SonatypeService._
import xerial.sbt.sonatype.{SonatypeClient, SonatypeException, SonatypeService}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/** Plugin for automating release processes at Sonatype Nexus
  */
object Sonatype extends AutoPlugin with LogSupport {
  wvlet.log.Logger.init

  trait SonatypeKeys {
    val sonatypeRepository              = settingKey[String]("Sonatype repository URL: e.g. https://oss.sonatype.org/service/local")
    val sonatypeProfileName             = settingKey[String]("Profile name at Sonatype: e.g. org.xerial")
    val sonatypeCredentialHost          = settingKey[String]("Credential host. Default is oss.sonatype.org")
    val sonatypeDefaultResolver         = settingKey[Resolver]("Default Sonatype Resolver")
    val sonatypePublishTo               = settingKey[Option[Resolver]]("Default Sonatype publishTo target")
    val sonatypePublishToBundle         = settingKey[Option[Resolver]]("Default Sonatype publishTo target")
    val sonatypeTargetRepositoryProfile = settingKey[StagingRepositoryProfile]("Stating repository profile")
    val sonatypeProjectHosting =
      settingKey[Option[ProjectHosting]]("Shortcut to fill in required Maven Central information")
    val sonatypeSessionName     = settingKey[String]("Used for identifying a sonatype staging repository")
    val sonatypeTimeoutMillis   = settingKey[Int]("milliseconds before giving up Sonatype API requests")
    val sonatypeBundleClean     = taskKey[Unit]("Clean up the local bundle folder")
    val sonatypeBundleDirectory = settingKey[File]("Directory to create a bundle")
    val sonatypeBundleRelease   = taskKey[String]("Release a bundle to Sonatype")
    val sonatypeLogLevel        = settingKey[String]("log level: trace, debug, info warn, error")
  }

  object SonatypeKeys extends SonatypeKeys {}

  object autoImport extends SonatypeKeys {}

  override def trigger         = allRequirements
  override def projectSettings = sonatypeSettings

  import autoImport._
  import complete.DefaultParsers._

  private implicit val ec = ExecutionContext.global

  lazy val sonatypeSettings = Seq[Def.Setting[_]](
    sonatypeProfileName := organization.value,
    sonatypeRepository := "https://oss.sonatype.org/service/local",
    sonatypeCredentialHost := "oss.sonatype.org",
    sonatypeProjectHosting := None,
    publishMavenStyle := true,
    pomIncludeRepository := { _ =>
      false
    },
    credentials ++= {
      val alreadyContainsSonatypeCredentials: Boolean = credentials.value.exists {
        case d: DirectCredentials => d.host == sonatypeCredentialHost.value
        case _                    => false
      }
      if (!alreadyContainsSonatypeCredentials) {
        val env = sys.env.get(_)
        (for {
          username <- env("SONATYPE_USERNAME")
          password <- env("SONATYPE_PASSWORD")
        } yield Credentials(
          "Sonatype Nexus Repository Manager",
          sonatypeCredentialHost.value,
          username,
          password
        )).toSeq
      } else Seq.empty
    },
    homepage := homepage.value.orElse(sonatypeProjectHosting.value.map(h => url(h.homepage))),
    scmInfo := sonatypeProjectHosting.value.map(_.scmInfo).orElse(scmInfo.value),
    developers := {
      val derived = sonatypeProjectHosting.value.map(h => List(h.developer)).getOrElse(List.empty)
      if (developers.value.isEmpty) derived
      else developers.value
    },
    sonatypePublishTo := Some(sonatypeDefaultResolver.value),
    sonatypeBundleDirectory := {
      (ThisBuild / baseDirectory).value / "target" / "sonatype-staging" / s"${(ThisBuild / version).value}"
    },
    sonatypeBundleClean := {
      IO.delete(sonatypeBundleDirectory.value)
    },
    sonatypePublishToBundle := {
      if (version.value.endsWith("-SNAPSHOT")) {
        Some(Opts.resolver.sonatypeSnapshots)
      } else {
        Some(Resolver.file("sonatype-local-bundle", sonatypeBundleDirectory.value))
      }
    },
    sonatypeDefaultResolver := {
      val profileM = sonatypeTargetRepositoryProfile.?.value

      val staged = profileM.map { stagingRepoProfile =>
        "releases" at stagingRepoProfile.deployUrl
      }
      staged.getOrElse(if (version.value.endsWith("-SNAPSHOT")) {
        Opts.resolver.sonatypeSnapshots
      } else {
        Opts.resolver.sonatypeStaging
      })
    },
    sonatypeTimeoutMillis := 60 * 60 * 1000, // 60 minutes
    sonatypeSessionName := s"[sbt-sonatype] ${name.value} ${version.value}",
    sonatypeLogLevel := "info",
    commands ++= Seq(
      sonatypeBundleRelease,
      sonatypeBundleUpload,
      sonatypePrepare,
      sonatypeOpen,
      sonatypeClose,
      sonatypePromote,
      sonatypeDrop,
      sonatypeRelease,
      sonatypeClean,
      sonatypeReleaseAll,
      sonatypeDropAll,
      sonatypeLog,
      sonatypeStagingRepositoryProfiles,
      sonatypeStagingProfiles
    )
  )

  private def prepare(state: State, rest: SonatypeService): StagingRepositoryProfile = {
    val extracted      = Project.extract(state)
    val descriptionKey = extracted.get(sonatypeSessionName)
    state.log.info(s"Preparing a new staging repository for ${descriptionKey}")
    // Drop a previous staging repository if exists
    val dropTask = Future.apply(rest.dropIfExistsByKey(descriptionKey))
    // Create a new one
    val createTask = Future.apply(rest.createStage(descriptionKey))
    // Run two tasks in parallel
    val merged                     = dropTask.zip(createTask)
    val (droppedRepo, createdRepo) = Await.result(merged, Duration.Inf)
    createdRepo
  }

  private val sonatypeBundleRelease =
    newCommand("sonatypeBundleRelease", "Upload a bundle in sonatypeBundleDirectory and release it at Sonatype") {
      state: State =>
        withSonatypeService(state) { rest =>
          val repo       = prepare(state, rest)
          val extracted  = Project.extract(state)
          val bundlePath = extracted.get(sonatypeBundleDirectory)
          rest.uploadBundle(bundlePath, repo.deployUrl)
          rest.closeAndPromote(repo)
          updatePublishSettings(state, repo)
        }
    }

  private val sonatypeBundleUpload = newCommand("sonatypeBundleUpload", "Upload a bundle in sonatypeBundleDirectory") {
    state: State =>
      val extracted  = Project.extract(state)
      val bundlePath = extracted.get(sonatypeBundleDirectory)
      withSonatypeService(state) { rest =>
        val repo = extracted.getOpt(sonatypeTargetRepositoryProfile).getOrElse {
          val descriptionKey = extracted.get(sonatypeSessionName)
          rest.openOrCreateByKey(descriptionKey)
        }
        rest.uploadBundle(bundlePath, repo.deployUrl)
        updatePublishSettings(state, repo)
      }
  }

  private val sonatypePrepare = newCommand(
    "sonatypePrepare",
    "Clean (if exists) and create a staging repository for releasing the current version, then update publishTo"
  ) { state: State =>
    withSonatypeService(state) { rest =>
      val repo = prepare(state, rest)
      updatePublishSettings(state, repo)
    }
  }

  private val sonatypeOpen = newCommand(
    "sonatypeOpen",
    "Open (or create if not exists) to a staging repository for the current version, then update publishTo"
  ) { state: State =>
    withSonatypeService(state) { rest =>
      // Re-open or create a staging repository
      val descriptionKey = Project.extract(state).get(sonatypeSessionName)
      val repo           = rest.openOrCreateByKey(descriptionKey)
      updatePublishSettings(state, repo)
    }
  }

  private def updatePublishSettings(state: State, repo: StagingRepositoryProfile): State = {
    val extracted = Project.extract(state)
    // accumulate changes for settings for current project and all aggregates
    state.log.info(s"Updating sonatypePublishTo settings...")
    val newSettings: Seq[Def.Setting[_]] = extracted.currentProject.referenced.flatMap { ref =>
      Seq(
        ref / sonatypeTargetRepositoryProfile := repo
      )
    } ++ Seq(
      sonatypeTargetRepositoryProfile := repo
    )

    val next = extracted.appendWithoutSession(newSettings, state)
    next
  }

  private val sonatypeClose = commandWithRepositoryId("sonatypeClose", "") { (state: State, arg: Option[String]) =>
    val extracted = Project.extract(state)
    val repoID    = arg.orElse(extracted.getOpt(sonatypeTargetRepositoryProfile).map(_.repositoryId))
    withSonatypeService(state) { rest =>
      val repo1 = rest.findTargetRepository(Close, repoID)
      val repo2 = rest.closeStage(repo1)
      extracted.appendWithoutSession(Seq(sonatypeTargetRepositoryProfile := repo2), state)
    }
  }

  private val sonatypePromote = commandWithRepositoryId("sonatypePromote", "Promote a staging repository") {
    (state: State, arg: Option[String]) =>
      val extracted = Project.extract(state)
      val repoID    = arg.orElse(extracted.getOpt(sonatypeTargetRepositoryProfile).map(_.repositoryId))
      withSonatypeService(state) { rest =>
        val repo1 = rest.findTargetRepository(Promote, repoID)
        val repo2 = rest.promoteStage(repo1)
        extracted.appendWithoutSession(Seq(sonatypeTargetRepositoryProfile := repo2), state)
      }
  }

  private val sonatypeDrop = commandWithRepositoryId("sonatypeDrop", "Drop a staging repository") {
    (state: State, arg: Option[String]) =>
      val extracted = Project.extract(state)
      val repoID    = arg.orElse(extracted.getOpt(sonatypeTargetRepositoryProfile).map(_.repositoryId))
      withSonatypeService(state) { rest =>
        val repo1 = rest.findTargetRepository(Drop, repoID)
        val repo2 = rest.dropStage(repo1)
        extracted.appendWithoutSession(Seq(sonatypeTargetRepositoryProfile := repo2), state)
      }
  }

  private val sonatypeRelease =
    commandWithRepositoryId("sonatypeRelease", "Publish with sonatypeClose and sonatypePromote") {
      (state: State, arg: Option[String]) =>
        val extracted = Project.extract(state)
        val repoID    = arg.orElse(extracted.getOpt(sonatypeTargetRepositoryProfile).map(_.repositoryId))
        withSonatypeService(state) { rest =>
          val repo1 = rest.findTargetRepository(CloseAndPromote, repoID)
          val repo2 = rest.closeAndPromote(repo1)
          extracted.appendWithoutSession(Seq(sonatypeTargetRepositoryProfile := repo2), state)
        }
    }

  private val sonatypeClean =
    newCommand("sonatypeClean", "Clean a staging repository for the current version if it exists") { state: State =>
      val extracted = Project.extract(state)
      withSonatypeService(state) { rest =>
        val descriptionKey = extracted.get(sonatypeSessionName)
        rest.dropIfExistsByKey(descriptionKey)
        state
      }
    }

  private val sonatypeReleaseAll =
    commandWithRepositoryId("sonatypeReleaseAll", "Publish all staging repositories to Maven central") {
      (state: State, arg: Option[String]) =>
        withSonatypeService(state, arg) { rest =>
          val tasks = rest.stagingRepositoryProfiles().map { repo =>
            Future.apply(rest.closeAndPromote(repo))
          }
          val merged = Future.sequence(tasks)
          Await.result(merged, Duration.Inf)
          state
        }
    }

  private val sonatypeDropAll = commandWithRepositoryId("sonatypeDropAll", "Drop all staging repositories") {
    (state: State, arg: Option[String]) =>
      withSonatypeService(state, arg) { rest =>
        val dropTasks = rest.stagingRepositoryProfiles().map { repo =>
          Future.apply(rest.dropStage(repo))
        }
        val merged = Future.sequence(dropTasks)
        Await.result(merged, Duration.Inf)
        state
      }
  }

  private val sonatypeLog = newCommand("sonatypeLog", "Show staging activity logs at Sonatype") { state: State =>
    withSonatypeService(state) { rest =>
      val alist = rest.activities
      if (alist.isEmpty)
        warn("No staging log is found")
      for ((repo, activities) <- alist) {
        info(s"Staging activities of $repo:")
        for (a <- activities) {
          a.showProgress
        }
      }
      state
    }
  }

  private val sonatypeStagingRepositoryProfiles =
    newCommand("sonatypeStagingRepositoryProfiles", "Show the list of staging repository profiles") { state: State =>
      withSonatypeService(state) { rest =>
        val repos = rest.stagingRepositoryProfiles()
        if (repos.isEmpty)
          warn(s"No staging repository is found for ${rest.profileName}")
        else {
          info(s"Staging repository profiles (sonatypeProfileName:${rest.profileName}):")
          info(repos.mkString("\n"))
        }
        state
      }
    }

  private val sonatypeStagingProfiles = newCommand("sonatypeStagingProfiles", "Show the list of staging profiles") {
    state: State =>
      withSonatypeService(state) { rest =>
        val profiles = rest.stagingProfiles
        if (profiles.isEmpty)
          warn(s"No staging profile is found for ${rest.profileName}")
        else {
          info(s"Staging profiles (sonatypeProfileName:${rest.profileName}):")
          info(profiles.mkString("\n"))
        }
        state
      }
  }

  case class ProjectHosting(
      domain: String,
      user: String,
      fullName: Option[String],
      email: String,
      repository: String
  ) {
    def homepage  = s"https://$domain/$user/$repository"
    def scmUrl    = s"git@$domain:$user/$repository.git"
    def scmInfo   = ScmInfo(url(homepage), scmUrl)
    def developer = Developer(user, fullName.getOrElse(user), email, url(s"https://$domain/$user"))
  }

  object GitHubHosting {
    private val domain                                         = "github.com"
    def apply(user: String, repository: String, email: String) = ProjectHosting(domain, user, None, email, repository)
    def apply(user: String, repository: String, fullName: String, email: String) =
      ProjectHosting(domain, user, Some(fullName), email, repository)
  }

  object GitLabHosting {
    private val domain                                         = "gitlab.com"
    def apply(user: String, repository: String, email: String) = ProjectHosting(domain, user, None, email, repository)
    def apply(user: String, repository: String, fullName: String, email: String) =
      ProjectHosting(domain, user, Some(fullName), email, repository)
  }

  // aliases
  @deprecated("Use GitHubHosting (capital H) instead", "2.2")
  val GithubHosting = GitHubHosting
  @deprecated("Use GitLabHosting (capital L) instead", "2.2")
  val GitlabHosting = GitLabHosting

  private val repositoryIdParser: complete.Parser[Option[String]] =
    (Space ~> token(StringBasic, "(sonatype staging repository id)")).?.!!!(
      "invalid input. please input a repository id"
    )

  private val sonatypeProfileParser: complete.Parser[Option[String]] =
    (Space ~> token(StringBasic, "(sonatypeProfileName)")).?.!!!(
      "invalid input. please input sonatypeProfileName (e.g., org.xerial)"
    )

  private def getCredentials(extracted: Extracted, state: State) = {
    val (nextState, credential) = extracted.runTask(credentials, state)
    credential
  }

  private def withSonatypeService(state: State, profileName: Option[String] = None)(
      body: SonatypeService => State
  ): State = {
    val extracted = Project.extract(state)
    val logLevel  = LogLevel(extracted.get(sonatypeLogLevel))
    wvlet.log.Logger.setDefaultLogLevel(logLevel)
    val sonatypeClient = new SonatypeClient(
      repositoryUrl = extracted.get(sonatypeRepository),
      cred = getCredentials(extracted, state),
      credentialHost = extracted.get(sonatypeCredentialHost),
      timeoutMillis = extracted.get(sonatypeTimeoutMillis)
    )
    val service = new SonatypeService(
      sonatypeClient,
      profileName.getOrElse(extracted.get(sonatypeProfileName))
    )
    try {
      body(service)
    } catch {
      case e: SonatypeException =>
        error(e.toString)
        state.fail
      case e: Throwable =>
        error(e)
        state.fail
    } finally {
      service.close()
    }
  }

  private def newCommand(name: String, briefHelp: String)(body: State => State) = {
    Command.command(name, briefHelp, briefHelp)(body)
  }

  private def commandWithRepositoryId(name: String, briefHelp: String) =
    Command(name, (name, briefHelp), briefHelp)(_ => repositoryIdParser)(_)

}
