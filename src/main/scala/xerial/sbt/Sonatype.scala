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
    val sonatypeRepository               = settingKey[String]("Sonatype repository URL: e.g. https://oss.sonatype.org/service/local")
    val sonatypeProfileName              = settingKey[String]("Profile name at Sonatype: e.g. org.xerial")
    val sonatypeCredentialHost           = settingKey[String]("Credential host. Default is oss.sonatype.org")
    val sonatypeDefaultResolver          = settingKey[Resolver]("Default Sonatype Resolver")
    val sonatypePublishTo                = settingKey[Option[Resolver]]("Default Sonatype publishTo target")
    val sonatypeStagingRepositoryProfile = settingKey[StagingRepositoryProfile]("Stating repository profile")
    val sonatypeProjectHosting =
      settingKey[Option[ProjectHosting]]("Shortcut to fill in required Maven Central information")
    val sonatypeList = taskKey[Unit]("list staging repositories")
    val sonatypePrepare = taskKey[StagingRepositoryProfile](
      "Clean (if exists) and create a staging repository using a given description, then update publishTo")
    val sonatypeClean =
      taskKey[Option[StagingRepositoryProfile]]("Clean a staging repository using a given description")
    val sonatypeOpen =
      taskKey[StagingRepositoryProfile]("Open (or create if not exists) to a staging repository, then update publishTo")
    val sonatypeClose =
      inputKey[StagingRepositoryProfile]("Close a stage and clear publishTo if it was set by sonatypeOpen")
    val sonatypePromote =
      inputKey[StagingRepositoryProfile]("Promote a staging repository")
    val sonatypeDrop =
      inputKey[StagingRepositoryProfile]("Drop a staging repository")
    val sonatypeRelease =
      inputKey[StagingRepositoryProfile]("Publish with sonatypeClose and sonatypePromote")
    val sonatypeService     = taskKey[NexusRESTService]("Sonatype REST API client")
    val sonatypeSessionName = settingKey[String]("Used for identifying a sonatype staging repository")
  }

  object SonatypeKeys extends SonatypeKeys {}

  object autoImport extends SonatypeKeys {}

  override def trigger         = noTrigger
  override def projectSettings = sonatypeSettings

  import SonatypeCommand._
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
      val profileM     = sonatypeStagingRepositoryProfile.?.value

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
    sonatypeList := {
      val rest     = sonatypeService.value
      val profiles = rest.stagingProfiles
      val s        = state.value
      val log      = s.log
      if (profiles.isEmpty) {
        log.warn(s"No staging profile is found for ${rest.profileName}")
        s.fail
      } else {
        log.info(s"Staging profiles (profileName:${rest.profileName}):")
        log.info(profiles.mkString("\n"))
      }
    },
    sonatypePrepare := {
      val descriptionKey = sonatypeSessionName.value
      state.value.log.info(s"Preparing a new staging repository for ${descriptionKey}")
      val rest           = sonatypeService.value
      // Drop a previous staging repository if exists
      val dropTask = Future.apply(rest.dropIfExistsByKey(descriptionKey))
      // Create a new one
      val createTask = Future.apply(rest.createStage(descriptionKey))
      // Run two tasks in parallel
      val merged = dropTask.zip(createTask)
      val (droppedRepo, createdRepo) = Await.result(merged, Duration.Inf)
      updatePublishTo(state.value, createdRepo)
      createdRepo
    },
    sonatypeClean := {
      val rest           = sonatypeService.value
      val descriptionKey = sonatypeSessionName.value
      rest.dropIfExistsByKey(descriptionKey)
    },
    sonatypeOpen := {
      val rest = sonatypeService.value
      // Re-open or create a staging repository
      val repo = rest.openOrCreateByKey(sonatypeSessionName.value)
      updatePublishTo(state.value, repo)
      repo
    },
    sonatypeClose := {
      val args           = spaceDelimited("<arg>").parsed.headOption
      val repoID = args.headOption.orElse(sonatypeStagingRepositoryProfile.?.value.map(_.repositoryId))
      val rest           = sonatypeService.value
      val repo1          = rest.findTargetRepository(Close, repoID)
      val repo2          = rest.closeStage(repo1)
      val s              = state.value
      Project.extract(s).appendWithSession(Seq(sonatypeStagingRepositoryProfile := repo2), s)
      repo2
    },
    sonatypePromote := {
      val args           = spaceDelimited("<arg>").parsed.headOption
      val repoID = args.headOption.orElse(sonatypeStagingRepositoryProfile.?.value.map(_.repositoryId))
      val rest  = sonatypeService.value
      val repo1 = rest.findTargetRepository(Promote, repoID)
      val repo2 = rest.promoteStage(repo1)
      val s     = state.value
      Project.extract(s).appendWithSession(Seq(sonatypeStagingRepositoryProfile := repo2), s)
      repo2
    },
    sonatypeDrop := {
      val args           = spaceDelimited("<arg>").parsed.headOption
      val repoID = args.headOption.orElse(sonatypeStagingRepositoryProfile.?.value.map(_.repositoryId))
      val rest  = sonatypeService.value
      val repo1 = rest.findTargetRepository(Drop, repoID)
      val repo2 = rest.dropStage(repo1)
      val s     = state.value
      Project.extract(s).appendWithSession(Seq(sonatypeStagingRepositoryProfile := repo2), s)
      repo2
    },
    sonatypeRelease := {
      val args           = spaceDelimited("<arg>").parsed.headOption
      val repoID = args.headOption.orElse(sonatypeStagingRepositoryProfile.?.value.map(_.repositoryId))
      val rest  = sonatypeService.value
      val repo1 = rest.findTargetRepository(CloseAndPromote, repoID)
      val repo2 = rest.closeAndPromote(repo1)
      val s     = state.value
      Project.extract(s).appendWithoutSession(Seq(sonatypeStagingRepositoryProfile := repo2), s)
      repo2
    },
    commands ++= Seq(
      sonatypeDropAll,
      sonatypeReleaseAll,
      sonatypeLog,
      sonatypeStagingRepositoryProfiles,
      sonatypeStagingProfiles
    )
  )

  private def updatePublishTo(state: State, repo: StagingRepositoryProfile): State = {
    state.log.info(s"Updating publishTo settings ...")
    val extracted = Project.extract(state)
    // accumulate changes for settings for current project and all aggregates
    val newSettings: Seq[Def.Setting[_]] = extracted.currentProject.referenced.flatMap { ref =>
      Seq(
        ref / sonatypeStagingRepositoryProfile := repo,
        ref / publishTo := Some(sonatypeDefaultResolver.value)
      )
    } ++ Seq(
      sonatypeStagingRepositoryProfile := repo,
      publishTo := Some(sonatypeDefaultResolver.value)
    )

    val next = extracted.appendWithSession(newSettings, state)
    next
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

  object SonatypeCommand {
    import complete.DefaultParsers._



    /**
      * Parsing repository id argument
      */
    private def getCredentials(extracted: Extracted, state: State) = {
      val (nextState, credential) = extracted.runTask(credentials, state)
      credential
    }

    def getNexusRestService(state: State, profileName: Option[String] = None) = {
      val extracted = Project.extract(state)
      new NexusRESTService(
        state.log,
        extracted.get(sonatypeRepository),
        profileName.getOrElse(extracted.get(sonatypeProfileName)),
        getCredentials(extracted, state),
        extracted.get(sonatypeCredentialHost)
      )
    }

    private val repositoryIdParser: complete.Parser[Option[String]] =
      (Space ~> token(StringBasic, "(repositoryId)")).?.!!!("invalid input. please input repository name")

    private val sonatypeProfileParser: complete.Parser[Option[String]] =
      (Space ~> token(StringBasic, "(sonatypeProfile)")).?.!!!(
        "invalid input. please input sonatypeProfile (e.g., org.xerial)"
      )

    val sonatypeProfileDescriptionParser: complete.Parser[Either[String, (String, String)]] =
      Space ~>
        (token(StringBasic, "description") ||
          (token(StringBasic <~ Space, "sonatypeProfile") ~ token(StringBasic, "description")))

    private def commandWithRepositoryId(name: String, briefHelp: String) =
      Command(name, (name, briefHelp), briefHelp)(_ => repositoryIdParser)(_)

    private def commandWithSonatypeProfile(name: String, briefHelp: String) =
      Command(name, (name, briefHelp), briefHelp)(_ => sonatypeProfileParser)(_)

    private def commandWithSonatypeProfileDescription(name: String, briefHelp: String) =
      Command(name, (name, briefHelp), briefHelp)(_ => sonatypeProfileDescriptionParser)(_)

    def getUpdatedPublishTo(profileName: String, current: Option[Option[Resolver]]): Seq[Setting[_]] = {
      val result = for {
        currentIfSet     <- current
        currentPublishTo <- currentIfSet
        if profileName == currentPublishTo.name
        setting = publishTo := None
      } yield setting
      result.toSeq
    }

    val sonatypeReleaseAll: Command =
      commandWithSonatypeProfile("sonatypeReleaseAll", "Publish all staging repositories to Maven central") {
        (state, profileName) =>
          val rest = getNexusRestService(state, profileName)
          val tasks = rest.stagingRepositoryProfiles().map { repo =>
            Future.apply(rest.closeAndPromote(repo))
          }
          val merged = Future.sequence(tasks)
          Await.result(merged, Duration.Inf)
          state
      }

    val sonatypeDropAll: Command = commandWithSonatypeProfile("sonatypeDropAll", "Drop all staging repositories") {
      (state, profileName) =>
        val rest = getNexusRestService(state, profileName)
        val dropTasks = rest.stagingRepositoryProfiles().map { repo =>
          Future.apply(rest.dropStage(repo))
        }
        val merged = Future.sequence(dropTasks)
        Await.result(merged, Duration.Inf)
        state
    }

    val sonatypeLog: Command =
      Command.command("sonatypeLog", "Show repository activities", "Show staging activity logs at Sonatype") { state =>
        val rest  = getNexusRestService(state)
        val alist = rest.activities
        val log   = state.log
        if (alist.isEmpty)
          log.warn("No staging log is found")
        for ((repo, activities) <- alist) {
          log.info(s"Staging activities of $repo:")
          for (a <- activities) {
            a.log(log)
          }
        }
        state
      }

    val sonatypeStagingRepositoryProfiles = Command.command("sonatypeStagingRepositoryProfiles") { state =>
      val rest  = getNexusRestService(state)
      val repos = rest.stagingRepositoryProfiles()
      val log   = state.log
      if (repos.isEmpty)
        log.warn(s"No staging repository is found for ${rest.profileName}")
      else {
        log.info(s"Staging repository profiles (sonatypeProfileName:${rest.profileName}):")
        log.info(repos.mkString("\n"))
      }
      state
    }

    val sonatypeStagingProfiles = Command.command("sonatypeStagingProfiles") { state =>
      val rest     = getNexusRestService(state)
      val profiles = rest.stagingProfiles
      val log      = state.log
      if (profiles.isEmpty)
        log.warn(s"No staging profile is found for ${rest.profileName}")
      else {
        log.info(s"Staging profiles (sonatypeProfileName:${rest.profileName}):")
        log.info(profiles.mkString("\n"))
      }
      state
    }
  }

}
