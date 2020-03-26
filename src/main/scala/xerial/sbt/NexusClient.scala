package xerial.sbt

import java.io.{File, IOException}
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

import com.twitter.finagle.http.{MediaType, Request, Response}
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.{BasicCredentialsProvider, DefaultHttpClient, HttpClientBuilder}
import org.apache.http.{HttpResponse, HttpStatus}
import org.sonatype.spice.zapper.ParametersBuilder
import org.sonatype.spice.zapper.client.hc4.Hc4ClientBuilder
import sbt.io.IO
import sbt.{Credentials, DirectCredentials, Logger}
import wvlet.airframe.codec.MessageCodec
import wvlet.airframe.control.{ResultClass, Retry}
import wvlet.airframe.control.Retry.RetryContext
import wvlet.airframe.http.finagle.Finagle
import wvlet.airframe.json.JSON.JSONValue
import xerial.sbt.sonatype.SonatypeClient

import scala.concurrent.ExecutionException
import scala.io.Source
import scala.util.Try
import scala.xml.{Utility, XML}

import sonatype.SonatypeClient._

/**
  * Interface to access the REST API of Nexus
  * @param log
  * @param repositoryUrl
  * @param profileName
  * @param cred
  * @param credentialHost
  */
class NexusRESTService(
    sonatypClient: SonatypeClient,
    log: Logger,
    repositoryUrl: String,
    val profileName: String,
    cred: Seq[Credentials],
    credentialHost: String,
) {
  import xerial.sbt.NexusRESTService._

  val monitor = new ActivityMonitor(log)

  def findTargetRepository(command: CommandType, arg: Option[String]): StagingRepositoryProfile = {
    val repos = command match {
      case Close           => openRepositories
      case Promote         => closedRepositories
      case Drop            => stagingRepositoryProfiles()
      case CloseAndPromote => stagingRepositoryProfiles()
    }
    if (repos.isEmpty) {
      if (stagingProfiles.isEmpty) {
        log.error(s"No staging profile found for $profileName")
        log.error("Have you requested a staging profile and successfully published your signed artifact there?")
        throw new IllegalStateException(s"No staging profile found for $profileName")
      } else {
        throw new IllegalStateException(command.errNotFound)
      }
    }

    def findSpecifiedInArg(target: String) = {
      repos.find(_.repositoryId == target).getOrElse {
        log.error(s"Repository $target is not found")
        log.error(s"Specify one of the repository ids in:\n${repos.mkString("\n")}")
        throw new IllegalArgumentException(s"Repository $target is not found")
      }
    }

    arg.map(findSpecifiedInArg).getOrElse {
      if (repos.size > 1) {
        log.error(s"Multiple repositories are found:\n${repos.mkString("\n")}")
        log.error(
          s"Specify one of the repository ids in the command line or run sonatypeDropAll to cleanup repositories")
        throw new IllegalStateException("Found multiple staging repositories")
      } else {
        repos.head
      }
    }
  }

  def openRepositories   = stagingRepositoryProfiles().filter(_.isOpen).sortBy(_.repositoryId)
  def closedRepositories = stagingRepositoryProfiles().filter(_.isClosed).sortBy(_.repositoryId)

  def uploadBundle(localBundlePath: File, remoteUrl: String): Unit = {
    sonatypClient.uploadBundle(localBundlePath, remoteUrl)
  }

  def openOrCreateByKey(descriptionKey: String): StagingRepositoryProfile = {
    // Find the already opened profile or create a new one
    val repos = findStagingRepositoryProfilesWithKey(descriptionKey)
    if (repos.size > 1) {
      throw new IllegalStateException(
        s"Multiple staging repositories for ${descriptionKey} exists. Run sonatypeDropAll first to clean up old repositories")
    } else if (repos.size == 1) {
      val repo = repos.head
      log.info(s"Found a staging repository ${repo}")
      repo
    } else {
      // Create a new staging repository by appending [sbt-sonatype] prefix to its description so that we can find the repository id later
      log.info(s"No staging repository for ${descriptionKey} is found. Create a new one.")
      createStage(descriptionKey)
    }
  }

  def dropIfExistsByKey(descriptionKey: String): Option[StagingRepositoryProfile] = {
    // Drop the staging repository if exists
    val repos = findStagingRepositoryProfilesWithKey(descriptionKey)
    if (repos.isEmpty) {
      log.info(s"No previous staging repository for ${descriptionKey} was found")
      None
    } else {
      repos.map { repo =>
        log.info(s"Found a previous staging repository ${repo}")
        dropStage(repo)
      }.lastOption
    }
  }

  def findStagingRepositoryProfilesWithKey(descriptionKey: String): Seq[StagingRepositoryProfile] = {
    stagingRepositoryProfiles(warnIfMissing = false).filter(_.description == descriptionKey)
  }

  def stagingRepositoryProfiles(warnIfMissing: Boolean = true): Seq[StagingRepositoryProfile] = {
    // Note: using /staging/profile_repositories/(profile id) is preferred to reduce the response size,
    // but Sonatype API is quite slow (as of Sep 2019) so using a single request was much better.
    val response   = sonatypClient.stagingRepositoryProfiles
    val myProfiles = response.filter(_.profileName == profileName)
    if (myProfiles.isEmpty && warnIfMissing) {
      log.warn(s"No staging repository is found. Do publishSigned first.")
    }
    myProfiles
  }

  private def withCache[A: scala.reflect.runtime.universe.TypeTag](fileName: String, a: => A): A = {
    val codec     = MessageCodec.of[A]
    val cacheFile = new File(fileName)
    val value = if (cacheFile.exists() && cacheFile.length() > 0) {
      Try {
        val json = IO.read(cacheFile)
        codec.fromJson(json)
      }.getOrElse(a)
    } else {
      a
    }
    cacheFile.getParentFile.mkdirs()
    IO.write(cacheFile, codec.toJson(value))
    value
  }

  def stagingProfiles: Seq[StagingProfile] = {
    val profiles = withCache(s"target/sonatype-profile-${profileName}.json", sonatypClient.stagingProfiles)
    profiles.filter(_.name == profileName)
  }

  lazy val currentProfile: StagingProfile = {
    val profiles = stagingProfiles
    if (profiles.isEmpty) {
      throw new IllegalArgumentException(
        s"Profile ${profileName} is not found. Check your sonatypeProfileName setting in build.sbt")
    }
    profiles.head
  }

  def createStage(description: String = "Requested by sbt-sonatype plugin"): StagingRepositoryProfile = {
    sonatypClient.createStage(currentProfile, description)
  }

  private val retryer = Retry.withJitter(maxRetry = 100, maxIntervalMillis = 60000)

  def closeStage(repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    if (repo.isClosed || repo.isReleased) {
      log.info(s"Repository ${repo.repositoryId} is already closed")
    } else {
      sonatypClient.closeStage(repo)
    }

    retryer
      .withResultClassifier[Option[ActivityEvent]] {
        case Some(activity: StagingActivity) =>
          if (activity.isCloseSucceeded(repo.repositoryId)) {
            log.info("Closed successfully")
            ResultClass.Succeeded
          } else if (activity.containsError) {
            log.error("Failed to close the repository")
            activity.reportFailure
            ResultClass.nonRetryableFailure(new Exception("Failed to close the repository"))
          } else {
            ResultClass.retryableFailure(new Exception("Waiting for the completion of the close process..."))
          }
      }
      .run {
        val activities = sonatypClient.activitiesOf(repo)
        monitor.report(activities)
        activities.lastOption
      }

    repo.toClosed
  }

  def dropStage(repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    val ret = sonatypClient.dropStage(currentProfile, repo)
    if (ret.statusCode != HttpStatus.SC_CREATED) {
      throw new IOException(s"Failed to drop ${repo.repositoryId}: ${ret.status}")
    }
    log.info(s"Dropped successfully: ${repo.repositoryId}")
    repo.toDropped
  }

  def promoteStage(repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    if (repo.isReleased) {
      log.info(s"Repository ${repo.repositoryId} is already released")
    } else {
      // Post promote(release) request
      val ret = sonatypClient.promoteStage(currentProfile, repo)
      if (ret.statusCode != HttpStatus.SC_CREATED) {
        log.error(s"${ret.status}: ${ret.contentString}")
        throw new Exception("Failed to promote the repository")
      }
    }

    val lastActivity =
      retryer
        .withResultClassifier[Option[StagingActivity]] {
          case Some(activity) =>
            if (activity.isReleaseSucceeded(repo.repositoryId)) {
              log.info("Promoted successfully")
              ResultClass.Succeeded
            } else if (activity.containsError) {
              log.error("Failed to promote the repository")
              activity.reportFailure
              Retry.nonRetryableFailure(new Exception("Failed to promote the repository"))
            } else {
              Retry.retryableFailure(new Exception("Waiting for the completion of the release process..."))
            }
        }
        .run {
          val activities = sonatypClient.activitiesOf(repo)
          monitor.report(activities)
          activities.filter(_.name == "release").lastOption
        }

    val droppedRepo = lastActivity
      .map { x =>
        // Drop after release
        dropStage(repo.toReleased)
      }
      .getOrElse {
        throw new IOException("Timed out")
      }

    droppedRepo
  }

  def stagingRepositoryInfo(repositoryId: String) = {
    sonatypClient.stagingRepository(repositoryId)
  }

  def closeAndPromote(repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    if (repo.isReleased) {
      dropStage(repo)
    } else {
      val closed = closeStage(repo)
      promoteStage(closed)
    }
  }

}

object NexusRESTService {

  /**
    * Switches of a Sonatype command to use
    */
  sealed trait CommandType {
    def errNotFound: String
  }
  case object Close extends CommandType {
    def errNotFound = "No open repository is found. Run publishSigned first"
  }
  case object Promote extends CommandType {
    def errNotFound = "No closed repository is found. Run publishSigned and close commands"
  }
  case object Drop extends CommandType {
    def errNotFound = "No staging repository is found. Run publishSigned first"
  }
  case object CloseAndPromote extends CommandType {
    def errNotFound = "No staging repository is found. Run publishSigned first"
  }

}
