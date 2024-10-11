package xerial.sbt.sonatype

import java.io.{File, IOException}
import java.net.URI
import java.util.concurrent.TimeUnit
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.impl.client.BasicCredentialsProvider
import org.sonatype.spice.zapper.client.hc4.Hc4ClientBuilder
import org.sonatype.spice.zapper.ParametersBuilder
import sbt.librarymanagement.ivy.Credentials
import scala.annotation.nowarn
import scala.concurrent.duration.Duration
import wvlet.airframe.control.{Control, ResultClass, Retry}
import wvlet.airframe.http.*
import wvlet.airframe.http.client.URLConnectionClientBackend
import wvlet.airframe.http.HttpHeader.MediaType
import wvlet.airframe.http.HttpMessage.Response
import wvlet.log.LogSupport
import xerial.sbt.sonatype.SonatypeException.{BUNDLE_UPLOAD_FAILURE, STAGE_FAILURE, STAGE_IN_PROGRESS}

/** REST API Client for Sonatype API (nexus-staging)
  * https://repository.sonatype.org/nexus-staging-plugin/default/docs/rest.html
  */
class SonatypeClient(
    repositoryUrl: String,
    cred: Seq[Credentials],
    credentialHost: String,
    timeoutMillis: Int = 3 * 60 * 60 * 1000
) extends AutoCloseable
    with LogSupport {

  private lazy val sonatypeCredentials = SonatypeCredentials.fromEnvOrError(cred, credentialHost)

  private lazy val base64Credentials = sonatypeCredentials.toBase64

  lazy val repoUri: URI = URI.create(repositoryUrl.stripSuffix("/"))

  private val pathPrefix = repoUri.getPath

  @nowarn("msg=URLConnectionClientBackend")
  private[sonatype] val clientConfig = {
    var config = Http.client
      .withName("sonatype-client")
      // TODO Enable client-side logging while avoiding Java8 compatibility of log-rotator (logback-core 1.5.x)
      .noLogging
      // Disables the circuit breaker, because Sonatype can be down for a long time https://github.com/xerial/sbt-sonatype/issues/363
      .noCircuitBreaker
      // Use URLConnectionClient for JDK8 compatibility. Remove this line when using JDK11 or later
      .withBackend(URLConnectionClientBackend)
      .withJSONEncoding
      // Need to set a longer timeout as Sonatype API may not respond quickly
      .withReadTimeout(Duration(timeoutMillis, TimeUnit.MILLISECONDS))
      // airframe-http will retry the request several times within this timeout duration.
      .withRetryContext { context =>
        // For individual REST calls, use a normal jittering
        context
          .withMaxRetry(1000)
          .withJitter(initialIntervalMillis = 1500, maxIntervalMillis = 30000)
      }
      .withRequestFilter { request =>
        request.withContentTypeJson
          .withAccept(MediaType.ApplicationJson)
          .withHeader(HttpHeader.Authorization, s"Basic ${base64Credentials}")
      }

    val javaVersion = sys.props.getOrElse("java.version", "unknown")
    if (javaVersion.startsWith("1.")) {
      warn(
        s"Disabled http client logging as Java version ${javaVersion} is no longer supported. Please use Java 17 or later."
      )
      config = config.noLogging
    } else {
      // Put the log file under target/sbt-sonatype directory
      config = config.withLoggerConfig {
        _.withLogFileName("target/sbt-sonatype/sonatype_client_logs.json")
      }
    }
    config
  }

  private[sonatype] val httpClient = clientConfig.newSyncClient(repoUri.toString)

  // Create stage is not idempotent, so we just need to wait for a long time without retry
  private val httpClientForCreateStage =
    clientConfig
      .withRetryContext(_.noRetry)
      .newSyncClient(repoUri.toString)

  override def close(): Unit = {
    Control.closeResources(httpClient, httpClientForCreateStage)
  }

  import xerial.sbt.sonatype.SonatypeClient.*

  def stagingRepositoryProfiles: Seq[StagingRepositoryProfile] = {
    info("Reading staging repository profiles...")
    val result =
      httpClient.readAs[Map[String, Seq[StagingRepositoryProfile]]](
        Http.GET(s"${pathPrefix}/staging/profile_repositories")
      )
    result.getOrElse("data", Seq.empty)
  }

  def stagingRepository(repositoryId: String) = {
    info(s"Searching for repository ${repositoryId} ...")
    httpClient.readAs[String](Http.GET(s"${pathPrefix}/staging/repository/${repositoryId}"))
  }

  def stagingProfiles: Seq[StagingProfile] = {
    info("Reading staging profiles...")
    val result = httpClient.readAs[StagingProfileResponse](Http.GET(s"${pathPrefix}/staging/profiles"))
    result.data
  }

  def createStage(profile: StagingProfile, description: String): StagingRepositoryProfile = {
    info(s"Creating a staging repository in profile ${profile.name} with a description key: ${description}")
    val ret = httpClientForCreateStage.call[Map[String, Map[String, String]], CreateStageResponse](
      Http.POST(s"${pathPrefix}/staging/profiles/${profile.id}/start"),
      Map("data" -> Map("description" -> description))
    )
    // Extract created staging repository ids
    val repo = StagingRepositoryProfile(
      profile.id,
      profile.name,
      "open",
      repositoryId = ret.data.stagedRepositoryId,
      ret.data.description
    )
    info(s"Created successfully: ${repo.repositoryId}")
    repo
  }

  private val monitor = new ActivityMonitor()

  /** backoff retry (max 15 sec. / each http request) until the timeout reaches (upto 60 min by default)
    */
  private val retryer = {
    val maxInterval  = 15000
    val initInterval = 3000
    // init * (multiplier ^ n) = max
    // n = log(max / init) / log(multiplier)
    val retryCountUntilMaxInterval = (math.log(maxInterval.toDouble / initInterval) / math.log(1.5)).toInt.max(1)
    val numRetry                   = (timeoutMillis / maxInterval).toDouble.ceil.toInt
    Retry.withBackOff(
      maxRetry = retryCountUntilMaxInterval + numRetry,
      initialIntervalMillis = initInterval,
      maxIntervalMillis = maxInterval
    )
  }

  private def waitForStageCompletion(
      taskName: String,
      repo: StagingRepositoryProfile,
      terminationCond: StagingActivity => Boolean
  ): StagingRepositoryProfile = {
    retryer
      .beforeRetry { ctx =>
        ctx.lastError match {
          case SonatypeException(STAGE_IN_PROGRESS, msg) =>
            info(f"${msg} Waiting for ${ctx.nextWaitMillis / 1000.0}%.2f sec.")
          case _ =>
            warn(
              f"[${ctx.retryCount}/${ctx.maxRetry}] Execution failed: ${ctx.lastError.getMessage}. Retrying in ${ctx.nextWaitMillis / 1000.0}%.2f sec."
            )
        }
      }
      .withResultClassifier[Option[StagingActivity]] {
        case Some(activity) if terminationCond(activity) =>
          info(s"[${taskName}] Finished successfully")
          ResultClass.Succeeded
        case Some(activity) if (activity.containsError) =>
          error(s"[${taskName}] Failed")
          activity.reportFailure
          ResultClass.nonRetryableFailure(SonatypeException(STAGE_FAILURE, s"Failed to ${taskName} the repository."))
        case _ =>
          ResultClass.retryableFailure(SonatypeException(STAGE_IN_PROGRESS, s"The ${taskName} stage is in progress."))
      }
      .run {
        val activities = activitiesOf(repo)
        monitor.report(activities)
        activities.lastOption
      }

    repo
  }

  def closeStage(currentProfile: StagingProfile, repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    info(s"Closing staging repository $repo")
    val ret = httpClient.call[Map[String, StageTransitionRequest], Response](
      Http.POST(s"${pathPrefix}/staging/profiles/${repo.profileId}/finish"),
      newStageTransitionRequest(currentProfile, repo)
    )
    if (ret.statusCode != HttpStatus.Created_201.code) {
      throw SonatypeException(STAGE_FAILURE, s"Failed to close the repository. [${ret.status}]: ${ret.contentString}")
    }
    waitForStageCompletion(
      "close",
      repo,
      terminationCond = {
        _.isCloseSucceeded(repo.repositoryId)
      }
    ).toClosed
  }

  def promoteStage(currentProfile: StagingProfile, repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    info(s"Promoting staging repository $repo")
    val ret = httpClient.call[Map[String, StageTransitionRequest], Response](
      Http.POST(s"${pathPrefix}/staging/profiles/${repo.profileId}/promote"),
      newStageTransitionRequest(currentProfile, repo)
    )
    if (ret.statusCode != HttpStatus.Created_201.code) {
      throw SonatypeException(STAGE_FAILURE, s"Failed to promote the repository. [${ret.status}]: ${ret.contentString}")
    }

    waitForStageCompletion("promote", repo, terminationCond = { _.isReleaseSucceeded(repo.repositoryId) })
  }

  def dropStage(currentProfile: StagingProfile, repo: StagingRepositoryProfile): Response = {
    info(s"Dropping staging repository $repo")
    try {
      val ret = httpClient.call[Map[String, StageTransitionRequest], Response](
        Http.POST(s"${pathPrefix}/staging/profiles/${repo.profileId}/drop"),
        newStageTransitionRequest(currentProfile, repo)
      )
      if (ret.statusCode != HttpStatus.Created_201.code) {
        throw SonatypeException(STAGE_FAILURE, s"Failed to drop the repository. [${ret.status}]: ${ret.contentString}")
      }
      ret
    } catch {
      case e: HttpClientException if e.status == HttpStatus.NotFound_404 =>
        warn(s"Staging repository ${repo.profileId} is not found. It might already have been dropped: ${e.getMessage}")
        e.response.toHttpResponse
    }
  }

  private def newStageTransitionRequest(
      currentProfile: StagingProfile,
      repo: StagingRepositoryProfile
  ): Map[String, StageTransitionRequest] = {
    Map(
      "data" -> StageTransitionRequest(
        stagedRepositoryId = repo.repositoryId,
        targetRepositoryId = currentProfile.repositoryTargetId,
        description = repo.description
      )
    )
  }

  def activitiesOf(r: StagingRepositoryProfile): Seq[StagingActivity] = {
    debug(s"Checking activity logs of ${r.repositoryId} ...")
    httpClient.readAs[Seq[StagingActivity]](Http.GET(s"${pathPrefix}/staging/repository/${r.repositoryId}/activity"))
  }

  def uploadBundle(localBundlePath: File, deployPath: String): Unit = {
    retryer
      .retryOn {
        case e: IOException if e.getMessage.contains("502 Bad Gateway") =>
          // #303 502 can be returned during the bundle upload
          Retry.retryableFailure(e)
        case e: IOException if e.getMessage.contains("Operation timed out") =>
          // #223 SSLException
          Retry.retryableFailure(e)
        case e: IOException if e.getMessage.contains("400 Bad Request") =>
          Retry.nonRetryableFailure(
            SonatypeException(
              BUNDLE_UPLOAD_FAILURE,
              s"Bundle upload failed. Probably a previously uploaded bundle remains. Run sonatypeClean or sonatypeDropAll first: ${e.getMessage}"
            )
          )
      }
      .run {
        val parameters = ParametersBuilder.defaults().build()
        // Adding a trailing slash is necessary to upload a bundle file to a proper location:
        val endpoint      = s"${repositoryUrl}/${deployPath}/"
        val clientBuilder = new Hc4ClientBuilder(parameters, endpoint)

        val credentialProvider = new BasicCredentialsProvider()
        val usernamePasswordCredentials =
          new UsernamePasswordCredentials(sonatypeCredentials.userName, sonatypeCredentials.password)

        credentialProvider.setCredentials(AuthScope.ANY, usernamePasswordCredentials)

        clientBuilder.withPreemptiveRealm(credentialProvider)

        import org.sonatype.spice.zapper.fs.DirectoryIOSource
        val deployables = new DirectoryIOSource(localBundlePath)

        val client = clientBuilder.build()
        try {
          info(s"Uploading bundle ${localBundlePath} to ${endpoint}")
          client.upload(deployables)
          info(s"Finished bundle upload: ${localBundlePath}")
        } finally {
          client.close()
        }
      }
  }

}

object SonatypeClient extends LogSupport {

  case class StagingProfileResponse(data: Seq[StagingProfile] = Seq.empty)

  /** Staging profile is the information associated to a Sonatype account.
    */
  case class StagingProfile(id: String, name: String, repositoryTargetId: String)

  /** Staging repository profile has an id of deployed artifact and the current staging state.
    */
  case class StagingRepositoryProfile(
      profileId: String,
      profileName: String,
      `type`: String,
      repositoryId: String,
      description: String
  ) {
    def stagingType: String = `type`

    override def toString =
      s"[$repositoryId] status:$stagingType, profile:$profileName($profileId) description: $description"
    def isOpen     = stagingType == "open"
    def isClosed   = stagingType == "closed"
    def isReleased = stagingType == "released"

    def toClosed   = copy(`type` = "closed")
    def toDropped  = copy(`type` = "dropped")
    def toReleased = copy(`type` = "released")

    def deployPath: String = s"staging/deployByRepositoryId/${repositoryId}"
  }

  case class CreateStageResponse(
      data: StagingRepositoryRef
  )
  case class StagingRepositoryRef(
      stagedRepositoryId: String,
      description: String
  )

  case class StageTransitionRequest(
      stagedRepositoryId: String,
      targetRepositoryId: String,
      description: String
  )

  case class Prop(name: String, value: String)

  /** ActivityEvent is an evaluation result (e.g., checksum, signature check, etc.) of a rule defined in a
    * StagingActivity ruleset
    * @param timestamp
    * @param name
    * @param severity
    * @param properties
    */
  case class ActivityEvent(timestamp: String, name: String, severity: Int, properties: Seq[Prop]) {
    lazy val map         = properties.map(x => x.name -> x.value).toMap
    def ruleType: String = map.getOrElse("typeId", "other")
    def isFailure        = name == "ruleFailed"

    override def toString = {
      s"-event -- timestamp:$timestamp, name:$name, severity:$severity, ${properties.map(p => s"${p.name}:${p.value}").mkString(", ")}"
    }

    def showProgress(useErrorLog: Boolean = false): Unit = {
      val props = {
        val front =
          if (map.contains("typeId"))
            Seq(map("typeId"))
          else
            Seq.empty
        front ++ map.filter(_._1 != "typeId").map(p => s"${p._1}:${p._2}")
      }
      val messageLine = props.mkString(", ")
      val name_s      = name.replaceAll("rule(s)?", "")
      val message     = f"$name_s%10s: $messageLine"
      if (useErrorLog)
        logger.error(message)
      else
        logger.info(message)
    }
  }

  class ActivityMonitor {
    var reportedActivities = Set.empty[String]
    var reportedEvents     = Set.empty[ActivityEvent]

    def report(stagingActivities: Seq[StagingActivity]) = {
      for (sa <- stagingActivities) {
        if (!reportedActivities.contains(sa.started)) {
          logger.info(sa.activityLog)
          reportedActivities += sa.started
        }
        for (ae <- sa.events if !reportedEvents.contains(ae)) {
          ae.showProgress(useErrorLog = false)
          reportedEvents += ae
        }
      }
    }
  }

  /** Staging activity is an action to the staged repository
    * @param name
    *   activity name, e.g. open, close, promote, etc.
    * @param started
    * @param stopped
    * @param events
    */
  case class StagingActivity(name: String, started: String, stopped: String, events: Seq[ActivityEvent]) {
    override def toString = {
      val b = Seq.newBuilder[String]
      b += activityLog
      for (e <- events)
        b += s" ${e.toString}"
      b.result.mkString("\n")
    }

    def activityLog = {
      val b = Seq.newBuilder[String]
      b += s"Activity name:${name}"
      b += s"started:${started}"
      if (stopped.nonEmpty) {
        b += s"stopped:${stopped}"
      }
      b.result().mkString(", ")
    }

    def showProgress: Unit = {
      logger.info(activityLog)
      val hasError = containsError
      for (e <- suppressEvaluateLog) {
        e.showProgress(hasError)
      }
    }

    def suppressEvaluateLog = {
      val in     = events.toIndexedSeq
      var cursor = 0
      val b      = Seq.newBuilder[ActivityEvent]
      while (cursor < in.size) {
        val current = in(cursor)
        if (cursor < in.size - 1) {
          val next = in(cursor + 1)
          if (current.name == "ruleEvaluate" && current.ruleType == next.ruleType) {
            // skip
          } else {
            b += current
          }
        }
        cursor += 1
      }
      b.result
    }

    def containsError = events.exists(_.severity != 0)

    def failureReport = suppressEvaluateLog.filter(_.isFailure)

    def reportFailure: Unit = {
      logger.error(activityLog)
      for (e <- failureReport) {
        e.showProgress(useErrorLog = true)
      }
    }

    def isReleaseSucceeded(repositoryId: String): Boolean = {
      events
        .find(_.name == "repositoryReleased")
        .exists(_.map.getOrElse("id", "") == repositoryId)
    }

    def isCloseSucceeded(repositoryId: String): Boolean = {
      events
        .find(_.name == "repositoryClosed")
        .exists(_.map.getOrElse("id", "") == repositoryId)
    }
  }
}
