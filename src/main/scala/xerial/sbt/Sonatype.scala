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
  }

  object SonatypeKeys extends SonatypeKeys {}

  object autoImport extends SonatypeKeys {}

  override def trigger         = noTrigger
  override def projectSettings = sonatypeSettings

  import SonatypeCommand._
  import autoImport._

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
    commands ++= Seq(
      sonatypeList,
      sonatypePrepare,
      sonatypeClean,
      sonatypeOpen,
      sonatypeClose,
      sonatypePromote,
      sonatypeDrop,
      sonatypeDropAll,
      sonatypeRelease,
      sonatypeReleaseAll,
      sonatypeLog,
      sonatypeStagingRepositoryProfiles,
      sonatypeStagingProfiles
    )
  )

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

    private implicit val ec = ExecutionContext.global

    /**
      * Parsing repository id argument
      */
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

    val sonatypeList: Command =
      Command.command("sonatypeList", "List staging repositories", "List published repository IDs") { state =>
        val rest     = getNexusRestService(state)
        val profiles = rest.stagingProfiles
        val log      = state.log
        if (profiles.isEmpty) {
          log.warn(s"No staging profile is found for ${rest.profileName}")
          state.fail
        } else {
          log.info(s"Staging profiles (profileName:${rest.profileName}):")
          log.info(profiles.mkString("\n"))
          state
        }
      }

    private val repositoryIdParser: complete.Parser[Option[String]] =
      (Space ~> token(StringBasic, "(repositoryId)")).?.!!!("invalid input. please input repository name")

    private val sonatypeProfileParser: complete.Parser[Option[String]] =
      (Space ~> token(StringBasic, "(sonatypeProfile)")).?.!!!(
        "invalid input. please input sonatypeProfile (e.g., org.xerial)"
      )

    private val sonatypeProfileDescriptionParser: complete.Parser[Either[String, (String, String)]] =
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

    private def descriptionKeyOf(profileDescription: String) = s"[sbt-sonatype] ${profileDescription}"

    private def updatePublishTo(state:State, repo:StagingRepositoryProfile): State = {
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

    val sonatypeOpen: Command =
      commandWithSonatypeProfileDescription("sonatypeOpen", "Create a staging repository and set publishTo") {
        (state, profileNameDescription) =>
          val (profileName: Option[String], profileDescription: String) = profileNameDescription match {
            case Left(d) =>
              (None, d)
            case Right((n, d)) =>
              (Some(n), d)
          }
          val rest = getNexusRestService(state, profileName)

          // Re-open or create a staging repository
          val repo = rest.openOrCreate(descriptionKeyOf(profileDescription))

          updatePublishTo(state, repo)
      }

    val sonatypePrepare: Command = {
      commandWithSonatypeProfileDescription(
        "sonatypePrepare",
        "Clean (if exists) and create a staging repository using a given description") {
        (state, profileNameDescription) =>
          val (profileName: Option[String], profileDescription: String) = profileNameDescription match {
            case Left(d) =>
              (None, d)
            case Right((n, d)) =>
              (Some(n), d)
          }
          val rest           = getNexusRestService(state, profileName)
          val descriptionKey = descriptionKeyOf(profileDescription)

          // Drop a previous one
          rest.dropIfExistsByKey(descriptionKey)
          // Create a new one
          val repo = rest.createStage(descriptionKey)

          updatePublishTo(state, repo)
      }
    }

    val sonatypeClean: Command = {
      commandWithSonatypeProfileDescription("sonatypeClean", "Clean a staging repository using a given description") {
        (state, profileNameDescription) =>
          val (profileName: Option[String], profileDescription: String) = profileNameDescription match {
            case Left(d) =>
              (None, d)
            case Right((n, d)) =>
              (Some(n), d)
          }
          val rest = getNexusRestService(state, profileName)
          val descriptionKey = descriptionKeyOf(profileDescription)
          rest.dropIfExistsByKey(descriptionKey)
          state
      }
    }

    val sonatypeClose: Command =
      commandWithRepositoryId("sonatypeClose", "Close a stage and clear publishTo if it was set by sonatypeOpen") {
        (state, parsed) =>
          val rest      = getNexusRestService(state)
          val extracted = Project.extract(state)
          val currentRepoID = for {
            repo <- extracted.getOpt(sonatypeStagingRepositoryProfile)
          } yield repo.repositoryId
          val repoID = parsed.orElse(currentRepoID)
          val repo1  = rest.findTargetRepository(Close, repoID)
          val repo2  = rest.closeStage(repo1)
          val next   = extracted.appendWithSession(Seq(sonatypeStagingRepositoryProfile := repo2), state)
          next
      }

    val sonatypePromote: Command = commandWithRepositoryId("sonatypePromote", "Promote a staged repository") {
      (state, parsed) =>
        val rest      = getNexusRestService(state)
        val extracted = Project.extract(state)
        val currentRepoID = for {
          repo <- extracted.getOpt(sonatypeStagingRepositoryProfile)
        } yield repo.repositoryId
        val repoID = parsed.orElse(currentRepoID)
        val repo1  = rest.findTargetRepository(Promote, repoID)
        val repo2  = rest.promoteStage(repo1)
        val next   = extracted.appendWithSession(Seq(sonatypeStagingRepositoryProfile := repo2), state)
        next
    }

    val sonatypeDrop: Command = commandWithRepositoryId("sonatypeDrop", "Drop a staging repository") {
      (state, parsed) =>
        val rest      = getNexusRestService(state)
        val extracted = Project.extract(state)
        val currentRepoID = for {
          repo <- extracted.getOpt(sonatypeStagingRepositoryProfile)
        } yield repo.repositoryId
        val repoID = parsed.orElse(currentRepoID)
        val repo1  = rest.findTargetRepository(Drop, repoID)
        val repo2  = rest.dropStage(repo1)
        val next   = extracted.appendWithSession(Seq(sonatypeStagingRepositoryProfile := repo2), state)
        next
    }

    val sonatypeRelease: Command =
      commandWithRepositoryId("sonatypeRelease", "Publish with sonatypeClose and sonatypePromote") { (state, parsed) =>
        val rest      = getNexusRestService(state)
        val extracted = Project.extract(state)
        val currentRepoID = for {
          repo <- extracted.getOpt(sonatypeStagingRepositoryProfile)
        } yield repo.repositoryId
        val repoID = parsed.orElse(currentRepoID)
        val repo1  = rest.findTargetRepository(CloseAndPromote, repoID)
        val repo2  = rest.closeAndPromote(repo1)
        val next   = extracted.appendWithoutSession(Seq(sonatypeStagingRepositoryProfile := repo2), state)
        next
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
