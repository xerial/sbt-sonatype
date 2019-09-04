package xerial.sbt

import java.io.IOException

import org.apache.http.{HttpResponse, HttpStatus}
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.{HttpGet, HttpPost}
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.DefaultHttpClient
import sbt.{Credentials, DirectCredentials, Logger}

import scala.io.Source
import scala.xml.{Utility, XML}

/**
  * Interface to access the REST API of Nexus
  * @param log
  * @param repositoryUrl
  * @param profileName
  * @param cred
  * @param credentialHost
  */
class NexusRESTService(
    log: Logger,
    repositoryUrl: String,
    val profileName: String,
    cred: Seq[Credentials],
    credentialHost: String
) {

  import NexusRESTService._

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
        log.error(s"Specify one of the repository ids in the command line")
        throw new IllegalStateException("Found multiple staging repositories")
      } else {
        repos.head
      }
    }
  }

  def openRepositories   = stagingRepositoryProfiles().filter(_.isOpen).sortBy(_.repositoryId)
  def closedRepositories = stagingRepositoryProfiles().filter(_.isClosed).sortBy(_.repositoryId)

  private def repoBase(url: String) = if (url.endsWith("/")) url.dropRight(1) else url
  private val repo = {
    val url = repoBase(repositoryUrl)
    log.info(s"Nexus repository URL: $url")
    log.info(s"sonatypeProfileName = ${profileName}")
    url
  }

  def Get[U](path: String)(body: HttpResponse => U): U = {
    val req = new HttpGet(s"${repo}$path")
    req.addHeader("Content-Type", "application/xml")

    val retry                  = new ExponentialBackOffRetry(initialWaitSeq = 0)
    var toContinue             = true
    var response: HttpResponse = null
    var ret: Any               = null
    while (toContinue && retry.hasNext) {
      withHttpClient { client =>
        response = client.execute(req)
        log.debug(s"Status line: ${response.getStatusLine}")
        response.getStatusLine.getStatusCode match {
          case HttpStatus.SC_OK =>
            toContinue = false
            ret = body(response)
          case HttpStatus.SC_INTERNAL_SERVER_ERROR =>
            log.warn(s"Received 500 error: ${response.getStatusLine}. Retrying...")
            retry.doWait
          case _ =>
            throw new IOException(s"Failed to retrieve data from $path: ${response.getStatusLine}")
        }
      }
    }
    if (ret == null) {
      throw new IOException(s"Failed to retrieve data from $path")
    }
    ret.asInstanceOf[U]
  }

  def IgnoreEntityContent(in: java.io.InputStream): Unit = ()

  def Post(path: String, bodyXML: String, contentHandler: java.io.InputStream => Unit = IgnoreEntityContent) = {
    val req = new HttpPost(s"${repo}$path")
    req.setEntity(new StringEntity(bodyXML))
    req.addHeader("Content-Type", "application/xml")

    val retry                  = new ExponentialBackOffRetry(initialWaitSeq = 0)
    var response: HttpResponse = null
    var toContinue             = true
    while (toContinue && retry.hasNext) {
      withHttpClient { client =>
        response = client.execute(req)
        response.getStatusLine.getStatusCode match {
          case HttpStatus.SC_INTERNAL_SERVER_ERROR =>
            log.warn(s"Received 500 error: ${response.getStatusLine}. Retrying...")
            retry.doWait
          case _ =>
            log.debug(s"Status line: ${response.getStatusLine}")
            toContinue = false
            contentHandler(response.getEntity.getContent)
        }
      }
    }
    if (toContinue) {
      throw new IOException(s"Failed to retrieve data from $path")
    }
    response
  }

  private def withHttpClient[U](body: HttpClient => U): U = {
    val credt: DirectCredentials = Credentials
      .forHost(cred, credentialHost)
      .getOrElse {
        throw new IllegalStateException(
          s"No credential is found for $credentialHost. Prepare ~/.sbt/(sbt_version)/sonatype.sbt file."
        )
      }

    val client = new DefaultHttpClient()
    try {
      val user   = credt.userName
      val passwd = credt.passwd
      client.getCredentialsProvider.setCredentials(
        new AuthScope(credt.host, AuthScope.ANY_PORT),
        new UsernamePasswordCredentials(user, passwd)
      )
      body(client)
    } finally client.getConnectionManager.shutdown()
  }

  def openOrCreate(descriptionKey: String): StagingRepositoryProfile = {
    // Create a new staging repository by appending [sbt-sonatype] prefix to its description so that we can find the repository id later
    def create = createStage(descriptionKey)

    // Find the already opened profile or create a new one
    findStagingRepositoryProfileWithKey(descriptionKey)
      .getOrElse(createStage(descriptionKey))
  }

  def dropIfExistsByKey(descriptionKey: String): Unit = {
    // Drop the staging repository if exists
    findStagingRepositoryProfileWithKey(descriptionKey)
      .map { repo =>
        log.info(s"Found a staging repository for ${descriptionKey}")
        dropStage(repo)
      }
      .getOrElse {
        log.info(s"No staging repository for ${descriptionKey} is found")
      }
  }

  def findStagingRepositoryProfileWithKey(descriptionKey: String): Option[StagingRepositoryProfile] = {
    stagingRepositoryProfiles(warnIfMissing = false).find(_.description == descriptionKey)
  }

  def stagingRepositoryProfiles(warnIfMissing: Boolean = true) = {
    log.info("Reading staging repository profiles...")
    // Note: using /stging/profile_repositories/(profile id) is preferred to reduce the response size,
    // but Sonatype API is quite slow (as of Sep 2019) so using a single request was much better.
    Get(s"/staging/profile_repositories") { response =>
      val profileRepositoriesXML = XML.load(response.getEntity.getContent)
      val repositoryProfiles = for (p <- profileRepositoriesXML \\ "stagingProfileRepository") yield {
        StagingRepositoryProfile(
          (p \ "profileId").text,
          (p \ "profileName").text,
          (p \ "type").text,
          (p \ "repositoryId").text,
          (p \ "description").text
        )
      }
      val myProfiles = repositoryProfiles.filter(_.profileName == profileName)
      if (myProfiles.isEmpty && warnIfMissing) {
        log.warn(s"No staging repository is found. Do publishSigned first.")
      }
      myProfiles
    }
  }

  def stagingProfiles = {
    log.info("Reading staging profiles...")
    Get("/staging/profiles") { response =>
      val profileXML = XML.load(response.getEntity.getContent)
      val profiles = for (p <- profileXML \\ "stagingProfile" if (p \ "name").text == profileName) yield {
        StagingProfile(
          (p \ "id").text,
          (p \ "name").text,
          (p \ "repositoryTargetId").text
        )
      }
      profiles
    }
  }

  lazy val currentProfile = {
    val profiles = stagingProfiles
    if (profiles.isEmpty) {
      throw new IllegalArgumentException(s"Profile ${profileName} is not found. Check your sonatypeProfileName setting in build.sbt")
    }
    profiles.head
  }

  private def createRequestXML(description: String) =
    s"""|<?xml version="1.0" encoding="UTF-8"?>
        |<promoteRequest>
        |  <data>
        |    <description>${Utility.escape(description)}</description>
        |  </data>
        |</promoteRequest>
         """.stripMargin

  private def promoteRequestXML(repo: StagingRepositoryProfile) =
    s"""|<?xml version="1.0" encoding="UTF-8"?>
        |<promoteRequest>
        |  <data>
        |    <stagedRepositoryId>${repo.repositoryId}</stagedRepositoryId>
        |    <targetRepositoryId>${currentProfile.repositoryTargetId}</targetRepositoryId>
        |    <description>${repo.description}</description>
        |  </data>
        |</promoteRequest>
         """.stripMargin

  class ExponentialBackOffRetry(initialWaitSeq: Int = 5, intervalSeq: Int = 3, maxRetries: Int = 10) {
    private var numTrial        = 0
    private var currentInterval = intervalSeq

    def hasNext = numTrial < maxRetries

    def nextWait = {
      val interval = if (numTrial == 0) initialWaitSeq else currentInterval
      currentInterval = (currentInterval * 1.5 + 0.5).toInt
      numTrial += 1
      interval
    }

    def doWait: Unit = {
      val w = nextWait
      Thread.sleep(w * 1000)
    }

  }

  def createStage(description: String = "Requested by sbt-sonatype plugin"): StagingRepositoryProfile = {
    val postURL = s"/staging/profiles/${currentProfile.profileId}/start"
    log.info(
      s"Creating a staging repository in profile ${currentProfile.profileName} with a description key: ${description}")
    var repo: StagingRepositoryProfile = null
    val ret = Post(
      postURL,
      createRequestXML(description),
      (in: java.io.InputStream) => {
        val xml = XML.load(in)
        val ids = xml \\ "data" \ "stagedRepositoryId"
        if (1 != ids.size)
          throw new IOException(s"Failed to create repository in profile: ${currentProfile.profileName}")
        repo = StagingRepositoryProfile(
          currentProfile.profileId,
          currentProfile.profileName,
          "open",
          ids.head.text,
          description
        )
        log.info(s"Created successfully: ${repo.repositoryId}")
      }
    )
    if (ret.getStatusLine.getStatusCode != HttpStatus.SC_CREATED) {
      throw new IOException(
        s"Failed to create repository in profile: ${currentProfile.profileName}: ${ret.getStatusLine}"
      )
    }
    if (null == repo) {
      throw new IOException(
        s"Failed to create repository in profile: ${currentProfile.profileName}: no stagedRepositoryId"
      )
    }
    repo
  }

  def closeStage(repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    var toContinue = true
    if (repo.isClosed || repo.isReleased) {
      log.info(s"Repository ${repo.repositoryId} is already closed")
      toContinue = false
    }

    if (toContinue) {
      // Post close request
      val postURL = s"/staging/profiles/${currentProfile.profileId}/finish"
      log.info(s"Closing staging repository $repo")
      val ret = Post(postURL, promoteRequestXML(repo))
      if (ret.getStatusLine.getStatusCode != HttpStatus.SC_CREATED) {
        throw new IOException(s"Failed to send close operation: ${ret.getStatusLine}")
      }
    }

    toContinue = true
    val timer = new ExponentialBackOffRetry()
    while (toContinue && timer.hasNext) {
      val activities = activitiesOf(repo)
      monitor.report(activities)
      activities.filter(_.name == "close").lastOption match {
        case Some(activity) =>
          if (activity.isCloseSucceeded(repo.repositoryId)) {
            toContinue = false
            log.info("Closed successfully")
          } else if (activity.containsError) {
            log.error("Failed to close the repository")
            activity.reportFailure(log)
            throw new Exception("Failed to close the repository")
          } else {
            // Activity log exists, but the close phase is not yet terminated
            log.debug("Close process is in progress ...")
            timer.doWait
          }
        case None =>
          timer.doWait
      }
    }
    if (toContinue)
      throw new IOException("Timed out")

    repo.toClosed
  }

  def dropStage(repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    val postURL = s"/staging/profiles/${currentProfile.profileId}/drop"
    log.info(s"Dropping staging repository $repo")
    val ret = Post(postURL, promoteRequestXML(repo))
    if (ret.getStatusLine.getStatusCode != HttpStatus.SC_CREATED) {
      throw new IOException(s"Failed to drop ${repo.repositoryId}: ${ret.getStatusLine}")
    }
    log.info(s"Dropped successfully: ${repo.repositoryId}")
    repo.toDropped
  }

  def promoteStage(repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    var toContinue = true
    if (repo.isReleased) {
      log.info(s"Repository ${repo.repositoryId} is already released")
      toContinue = false
    }

    if (toContinue) {
      // Post promote(release) request
      val postURL = s"/staging/profiles/${currentProfile.profileId}/promote"
      log.info(s"Promoting staging repository $repo")
      val ret = Post(postURL, promoteRequestXML(repo))
      if (ret.getStatusLine.getStatusCode != HttpStatus.SC_CREATED) {
        log.error(s"${ret.getStatusLine}")
        for (errorLine <- Source.fromInputStream(ret.getEntity.getContent).getLines()) {
          log.error(errorLine)
        }
        throw new Exception("Failed to promote the repository")
      }
    }

    toContinue = true
    var result: StagingRepositoryProfile = null
    val timer                            = new ExponentialBackOffRetry()
    while (toContinue && timer.hasNext) {
      val activities = activitiesOf(repo)
      monitor.report(activities)
      activities.filter(_.name == "release").lastOption match {
        case Some(activity) =>
          if (activity.isReleaseSucceeded(repo.repositoryId)) {
            log.info("Promoted successfully")

            // Drop after release
            result = dropStage(repo.toReleased)
            toContinue = false
          } else if (activity.containsError) {
            log.error("Failed to promote the repository")
            activity.reportFailure(log)
            throw new Exception("Failed to promote the repository")
          } else {
            log.debug("Release process is in progress ...")
            timer.doWait
          }
        case None =>
          timer.doWait
      }
    }
    if (toContinue)
      throw new IOException("Timed out")
    require(null != result)
    result
  }

  def stagingRepositoryInfo(repositoryId: String) = {
    log.info(s"Seaching for repository $repositoryId ...")
    val ret = Get(s"/staging/repository/$repositoryId") { response =>
      XML.load(response.getEntity.getContent)
    }
    ret
  }

  def closeAndPromote(repo: StagingRepositoryProfile): StagingRepositoryProfile = {
    if (repo.isReleased) {
      dropStage(repo)
    } else {
      val closed = closeStage(repo)
      promoteStage(closed)
    }
  }

  def activities: Seq[(StagingRepositoryProfile, Seq[StagingActivity])] = {
    for (r <- stagingRepositoryProfiles()) yield r -> activitiesOf(r)
  }

  def activitiesOf(r: StagingRepositoryProfile): Seq[StagingActivity] = {
    log.debug(s"Checking activity logs of ${r.repositoryId} ...")
    val a = Get(s"/staging/repository/${r.repositoryId}/activity") { response =>
      val xml = XML.load(response.getEntity.getContent)
      for (sa <- xml \\ "stagingActivity") yield {
        val ae = for (event <- sa \ "events" \ "stagingActivityEvent") yield {
          val props = for (prop <- event \ "properties" \ "stagingProperty") yield {
            (prop \ "name").text -> (prop \ "value").text
          }
          ActivityEvent((event \ "timestamp").text, (event \ "name").text, (event \ "severity").text, props.toMap)
        }
        StagingActivity((sa \ "name").text, (sa \ "started").text, (sa \ "stopped").text, ae.toSeq)
      }
    }
    a
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

  /**
    * Staging repository profile has an id of deployed artifact and the current staging state.
    * @param profileId
    * @param profileName
    * @param stagingType
    * @param repositoryId
    * @param description
    */
  case class StagingRepositoryProfile(
      profileId: String,
      profileName: String,
      stagingType: String,
      repositoryId: String,
      description: String
  ) {
    override def toString =
      s"[$repositoryId] status:$stagingType, profile:$profileName($profileId) description: $description"
    def isOpen     = stagingType == "open"
    def isClosed   = stagingType == "closed"
    def isReleased = stagingType == "released"

    def toClosed   = copy(stagingType = "closed")
    def toDropped  = copy(stagingType = "dropped")
    def toReleased = copy(stagingType = "released")
  }

  /**
    * Staging profile is the information associated to a Sonatype account.
    * @param profileId
    * @param profileName
    * @param repositoryTargetId
    */
  case class StagingProfile(profileId: String, profileName: String, repositoryTargetId: String)

  /**
    * Staging activity is an action to the staged repository
    * @param name activity name, e.g. open, close, promote, etc.
    * @param started
    * @param stopped
    * @param events
    */
  case class StagingActivity(name: String, started: String, stopped: String, events: Seq[ActivityEvent]) {
    override def toString = {
      val b = Seq.newBuilder[String]
      b += s"-activity -- name:$name, started:$started, stopped:$stopped"
      for (e <- events)
        b += s" ${e.toString}"
      b.result.mkString("\n")
    }

    def activityLog = s"Activity $name started:$started, stopped:$stopped"

    def log(log: Logger): Unit = {
      log.info(activityLog)
      val hasError = containsError
      for (e <- suppressEvaluateLog) {
        e.log(log, hasError)
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

    def containsError = events.exists(_.severity != "0")

    def reportFailure(log: Logger): Unit = {
      log.error(activityLog)
      val failureReport = suppressEvaluateLog.filter(_.isFailure)
      for (e <- failureReport) {
        e.log(log, useErrorLog = true)
      }
    }

    def isReleaseSucceeded(repositoryId: String): Boolean = {
      events
        .find(_.name == "repositoryReleased")
        .exists(_.property.getOrElse("id", "") == repositoryId)
    }

    def isCloseSucceeded(repositoryId: String): Boolean = {
      events
        .find(_.name == "repositoryClosed")
        .exists(_.property.getOrElse("id", "") == repositoryId)
    }

  }

  /**
    * ActivityEvent is an evaluation result (e.g., checksum, signature check, etc.) of a rule defined in a StagingActivity ruleset
    * @param timestamp
    * @param name
    * @param severity
    * @param property
    */
  case class ActivityEvent(timestamp: String, name: String, severity: String, property: Map[String, String]) {
    def ruleType: String = property.getOrElse("typeId", "other")
    def isFailure        = name == "ruleFailed"

    override def toString =
      s"-event -- timestamp:$timestamp, name:$name, severity:$severity, ${property.map(p => s"${p._1}:${p._2}").mkString(", ")}"

    def log(s: Logger, useErrorLog: Boolean = false): Unit = {
      val props = {
        val front =
          if (property.contains("typeId"))
            Seq(property("typeId"))
          else
            Seq.empty
        front ++ property.filter(_._1 != "typeId").map(p => s"${p._1}:${p._2}")
      }
      val messageLine = props.mkString(", ")
      val name_s      = name.replaceAll("rule(s)?", "")
      val message     = f"$name_s%10s: $messageLine"
      if (useErrorLog)
        s.error(message)
      else
        s.info(message)
    }
  }

  class ActivityMonitor(s: Logger) {
    var reportedActivities = Set.empty[String]
    var reportedEvents     = Set.empty[ActivityEvent]

    def report(stagingActivities: Seq[StagingActivity]) = {
      for (sa <- stagingActivities) {
        if (!reportedActivities.contains(sa.started)) {
          s.info(sa.activityLog)
          reportedActivities += sa.started
        }
        for (ae <- sa.events if !reportedEvents.contains(ae)) {
          ae.log(s, useErrorLog = false)
          reportedEvents += ae
        }
      }
    }
  }

}
