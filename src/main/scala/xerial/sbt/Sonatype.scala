//--------------------------------------
//
// Sonatype.scala
// Since: 2014/01/05
//
//--------------------------------------

package xerial.sbt

import sbt.Keys._
import sbt._
import xerial.sbt.NexusRESTService._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}

/**
  * Plugin for automating release processes at Sonatype Nexus
  */
object Sonatype extends AutoPlugin {

  trait SonatypeKeys {
    val sonatypeRepository           = settingKey[String]("Sonatype repository URL: e.g. https://oss.sonatype.org/service/local")
    val sonatypeProfileName          = settingKey[String]("Profile name at Sonatype: e.g. org.xerial")
    val sonatypeCredentialHost       = settingKey[String]("Credential host. Default is oss.sonatype.org")
    val sonatypeDefaultResolver      = settingKey[Resolver]("Default Sonatype Resolver")
    val sonatypePublishTo            = settingKey[Option[Resolver]]("Default Sonatype publishTo target")
    val sonatypeTargetStagingProfile = settingKey[StagingRepositoryProfile]("Stating repository profile")
    val sonatypeProjectHosting =
      settingKey[Option[ProjectHosting]]("Shortcut to fill in required Maven Central information")
    val sonatypeService     = taskKey[NexusRESTService]("Sonatype REST API client")
    val sonatypeSessionName = settingKey[String]("Used for identifying a sonatype staging repository")

    val sonatypeLog = taskKey[Unit]("Show staging activity logs at Sonatype")

    val sonatypeStagingRepositoryProfiles =
      taskKey[Seq[StagingRepositoryProfile]]("show the list of staging repository profiles")
    val sonatypeStagingProfiles = taskKey[Seq[StagingProfile]]("show the list of staging profiles")
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
      val alreadyContainsSonatypeCredentials = credentials.value.collect {
        case d: DirectCredentials => d.host == sonatypeCredentialHost.value
      }.nonEmpty
      if (!alreadyContainsSonatypeCredentials) {
        val env = sys.env.get(_)
        (for {
          username <- env("SONATYPE_USERNAME")
          password <- env("SONATYPE_PASSWORD")
        } yield
          Credentials(
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
    sonatypeDefaultResolver := {
      val sonatypeRepo = "https://oss.sonatype.org/"
      val profileM     = sonatypeTargetStagingProfile.?.value

      val staged = profileM.map { stagingRepoProfile =>
        "releases" at sonatypeRepo +
          "service/local/staging/deployByRepositoryId/" +
          stagingRepoProfile.repositoryId
      }
      staged.getOrElse(if (isSnapshot.value) {
        Opts.resolver.sonatypeSnapshots
      } else {
        Opts.resolver.sonatypeStaging
      })
    },
    sonatypeSessionName := s"[sbt-sonatype] ${name.value} ${version.value}",
    sonatypeService := {
      getNexusRestService(state.value, Some(sonatypeProfileName.value))
    },
    commands ++= Seq(
      sonatypePrepare,
      sonatypeOpen,
      sonatypeClose,
      sonatypePromote,
      sonatypeDrop,
      sonatypeRelease,
      sonatypeClean,
      sonatypeReleaseAll,
      sonatypeDropAll
    ),
    sonatypeLog := {
      val rest  = sonatypeService.value
      val alist = rest.activities
      val log   = state.value.log
      if (alist.isEmpty)
        log.warn("No staging log is found")
      for ((repo, activities) <- alist) {
        log.info(s"Staging activities of $repo:")
        for (a <- activities) {
          a.log(log)
        }
      }
    },
    sonatypeStagingRepositoryProfiles := {
      val rest  = sonatypeService.value
      val repos = rest.stagingRepositoryProfiles()
      val log   = state.value.log
      if (repos.isEmpty)
        log.warn(s"No staging repository is found for ${rest.profileName}")
      else {
        log.info(s"Staging repository profiles (sonatypeProfileName:${rest.profileName}):")
        log.info(repos.mkString("\n"))
      }
      repos
    },
    sonatypeStagingProfiles := {
      val rest     = sonatypeService.value
      val profiles = rest.stagingProfiles
      val log      = state.value.log
      if (profiles.isEmpty)
        log.warn(s"No staging profile is found for ${rest.profileName}")
      else {
        log.info(s"Staging profiles (sonatypeProfileName:${rest.profileName}):")
        log.info(profiles.mkString("\n"))
      }
      profiles
    }
  )

  private val sonatypePrepare = newCommand(
    "sonatypePrepare",
    "Clean (if exists) and create a staging repository for releasing the current version, then update publishTo") {
    state: State =>
      val extracted      = Project.extract(state)
      val descriptionKey = extracted.get(sonatypeSessionName)
      state.log.info(s"Preparing a new staging repository for ${descriptionKey}")
      val rest: NexusRESTService = getNexusRestService(state)
      // Drop a previous staging repository if exists
      val dropTask = Future.apply(rest.dropIfExistsByKey(descriptionKey))
      // Create a new one
      val createTask = Future.apply(rest.createStage(descriptionKey))
      // Run two tasks in parallel
      val merged                     = dropTask.zip(createTask)
      val (droppedRepo, createdRepo) = Await.result(merged, Duration.Inf)
      updatePublishTo(state, createdRepo)
  }

  private val sonatypeOpen = newCommand(
    "sonatypeOpen",
    "Open (or create if not exists) to a staging repository for the current version, then update publishTo") {
    state: State =>
      val rest = getNexusRestService(state)
      // Re-open or create a staging repository
      val descriptionKey = Project.extract(state).get(sonatypeSessionName)
      val repo           = rest.openOrCreateByKey(descriptionKey)
      updatePublishTo(state, repo)
  }

  private def updatePublishTo(state: State, repo: StagingRepositoryProfile): State = {
    val extracted = Project.extract(state)
    // accumulate changes for settings for current project and all aggregates
    state.log.info(s"Updating publishTo settings...")
    val newSettings: Seq[Def.Setting[_]] = extracted.currentProject.referenced.flatMap { ref =>
      Seq(
        ref / sonatypeTargetStagingProfile := repo,
        ref / publishTo := Some(sonatypeDefaultResolver.value)
      )
    } ++ Seq(
      sonatypeTargetStagingProfile := repo,
      publishTo := Some(sonatypeDefaultResolver.value)
    )

    val next = extracted.appendWithoutSession(newSettings, state)
    next
  }

  private val sonatypeClose = commandWithRepositoryId("sonatypeClose", "") { (state: State, arg: Option[String]) =>
    val extracted = Project.extract(state)
    val repoID    = arg.orElse(extracted.getOpt(sonatypeTargetStagingProfile).map(_.repositoryId))
    val rest      = getNexusRestService(state)
    val repo1     = rest.findTargetRepository(Close, repoID)
    val repo2     = rest.closeStage(repo1)
    extracted.appendWithoutSession(Seq(sonatypeTargetStagingProfile := repo2), state)
  }

  private val sonatypePromote = commandWithRepositoryId("sonatypePromote", "Promote a staging repository") {
    (state: State, arg: Option[String]) =>
      val extracted = Project.extract(state)
      val repoID    = arg.orElse(extracted.getOpt(sonatypeTargetStagingProfile).map(_.repositoryId))
      val rest      = getNexusRestService(state)
      val repo1     = rest.findTargetRepository(Promote, repoID)
      val repo2     = rest.promoteStage(repo1)
      extracted.appendWithoutSession(Seq(sonatypeTargetStagingProfile := repo2), state)
  }

  private val sonatypeDrop = commandWithRepositoryId("sonatypeDrop", "Drop a staging repository") {
    (state: State, arg: Option[String]) =>
      val extracted = Project.extract(state)
      val repoID    = arg.orElse(extracted.getOpt(sonatypeTargetStagingProfile).map(_.repositoryId))
      val rest      = getNexusRestService(state)
      val repo1     = rest.findTargetRepository(Drop, repoID)
      val repo2     = rest.dropStage(repo1)
      extracted.appendWithoutSession(Seq(sonatypeTargetStagingProfile := repo2), state)
  }

  private val sonatypeRelease =
    commandWithRepositoryId("sonatypeRelease", "Publish with sonatypeClose and sonatypePromote") {
      (state: State, arg: Option[String]) =>
        val extracted = Project.extract(state)
        val repoID    = arg.orElse(extracted.getOpt(sonatypeTargetStagingProfile).map(_.repositoryId))
        val rest      = getNexusRestService(state)
        val repo1     = rest.findTargetRepository(CloseAndPromote, repoID)
        val repo2     = rest.closeAndPromote(repo1)
        extracted.appendWithoutSession(Seq(sonatypeTargetStagingProfile := repo2), state)
    }

  private val sonatypeClean =
    newCommand("sonatypeClean", "Clean a staging repository for the current version if it exists") { state: State =>
      val extracted      = Project.extract(state)
      val rest           = getNexusRestService(state)
      val descriptionKey = extracted.get(sonatypeSessionName)
      rest.dropIfExistsByKey(descriptionKey)
      state
    }

  private val sonatypeReleaseAll =
    commandWithRepositoryId("sonatypeReleaseAll", "Publish all staging repositories to Maven central") {
      (state: State, arg: Option[String]) =>
        val rest = getNexusRestService(state, arg)
        val tasks = rest.stagingRepositoryProfiles().map { repo =>
          Future.apply(rest.closeAndPromote(repo))
        }
        val merged = Future.sequence(tasks)
        Await.result(merged, Duration.Inf)
        state
    }

  private val sonatypeDropAll = commandWithRepositoryId("sonatypeDropAll", "Drop all staging repositories") {
    (state: State, arg: Option[String]) =>
      val rest = getNexusRestService(state, arg)
      val dropTasks = rest.stagingRepositoryProfiles().map { repo =>
        Future.apply(rest.dropStage(repo))
      }
      val merged = Future.sequence(dropTasks)
      Await.result(merged, Duration.Inf)
      state
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
      "invalid input. please input a repository id")

  private val sonatypeProfileParser: complete.Parser[Option[String]] =
    (Space ~> token(StringBasic, "(sonatypeProfileName)")).?.!!!(
      "invalid input. please input sonatypeProfileName (e.g., org.xerial)"
    )

  private def getCredentials(extracted: Extracted, state: State) = {
    val (nextState, credential) = extracted.runTask(credentials, state)
    credential
  }

  private def getNexusRestService(state: State, profileName: Option[String] = None) = {
    val extracted = Project.extract(state)
    new NexusRESTService(
      state.log,
      extracted.get(sonatypeRepository),
      profileName.getOrElse(extracted.get(sonatypeProfileName)),
      getCredentials(extracted, state),
      extracted.get(sonatypeCredentialHost)
    )
  }

  private def newCommand(name: String, briefHelp: String)(body: State => State) = {
    Command.command(name, briefHelp, briefHelp)(body)
  }

  private def commandWithRepositoryId(name: String, briefHelp: String) =
    Command(name, (name, briefHelp), briefHelp)(_ => repositoryIdParser)(_)

}
