//--------------------------------------
//
// Sonatype.scala
// Since: 2014/01/05
//
//--------------------------------------

package xerial.sbt

import sbt._
import Keys._
import org.apache.http.auth.{AuthScope, UsernamePasswordCredentials}
import org.apache.http.impl.client.{DefaultHttpClient, BasicCredentialsProvider}
import org.apache.http.client.methods.{HttpPost, HttpGet}
import sbt.plugins.JvmPlugin
import scala.xml.XML
import org.apache.http.client.HttpClient
import org.apache.http.{HttpStatus, HttpResponse}
import org.apache.http.entity.StringEntity
import scala.io.Source
import java.io.IOException


/**
 * Plugin for automating release processes at Sonatype Nexus
 * @author Taro L. Saito
 */
object Sonatype extends AutoPlugin {

  object SonatypeKeys {
    val sonatypeRepository = settingKey[String]("Sonatype repository URL")
    val sonatypeProfileName = settingKey[String]("Profile name at Sonatype: e.g. org.xerial")
    val sonatypeClose = inputKey[Boolean]("Close the stage")
    val sonatypePromote = inputKey[Boolean]("Close and promoe the repository")
    val sonatypeDrop = inputKey[Boolean]("Drop the repository")
    val sonatypeRelease = inputKey[Boolean]("Publish to Maven central via sonatypeClose and sonatypePromote")
    val sonatypeReleaseAll = taskKey[Boolean]("Publish all staging repositories to Maven central")
    val sonatypeCredentialHost = settingKey[String]("Credential host. Default is oss.sonatype.org")
    private[Sonatype] val sonatypeRestService = taskKey[NexusRESTService]("REST API")
    val sonatypeList = taskKey[Unit]("List staging repositories")
    val sonatypeLog = taskKey[Unit]("Show repository activities")
    val sonatypeStagingRepositoryProfiles = taskKey[Seq[StagingRepositoryProfile]]("List staging repository profiles")
    val sonatypeStagingProfiles = taskKey[Seq[StagingProfile]]("List staging profiles")
    val sonatypeDefaultResolver = settingKey[Resolver]("Default Sonatype Resolver")
  }

  object autoImport {
    val SonatypeKeys = Sonatype.SonatypeKeys
  }

  override def trigger = allRequirements

  override def requires = JvmPlugin

  override def projectSettings = sonatypeSettings

  import complete.DefaultParsers._

  /**
   * Parsing repository id argument
   */
  private val repositoryIdParser: complete.Parser[Option[String]] =
    (Space ~> token(StringBasic, "(repositoryId)")).?.!!!("invalid input. please input repository name")

  import SonatypeKeys._

  lazy val sonatypeSettings : Seq[Def.Setting[_]] = Seq[Def.Setting[_]](
    // Add sonatype repository settings
    publishTo := {
      Some(sonatypeDefaultResolver.value)
    },
    publishMavenStyle := true,
    pomIncludeRepository := { _ => false }
  ) ++ sonatypeGlobalSettings

  lazy val sonatypeGlobalSettings = Seq[Def.Setting[_]](
    sonatypeDefaultResolver := {
      if (isSnapshot.value) {
        Opts.resolver.sonatypeSnapshots
      } else {
        Opts.resolver.sonatypeStaging
      }
    },
    sonatypeRepository := "https://oss.sonatype.org/service/local",
    sonatypeCredentialHost := "oss.sonatype.org",
    sonatypeProfileName := organization.value,
    sonatypeRestService := new NexusRESTService(streams.value, sonatypeRepository.value, sonatypeProfileName.value, credentials.value, sonatypeCredentialHost.value),
    sonatypeStagingRepositoryProfiles := {
      val rest : NexusRESTService = sonatypeRestService.value
      val s = streams.value
      val repos = rest.stagingRepositoryProfiles
      s.log.info("Staging repository profiles:")
      if(repos.isEmpty)
        s.log.warn("No staging repository is found.")
      else
        s.log.info(repos.mkString("\n"))
      repos
    },
    sonatypeStagingProfiles := {
      val rest : NexusRESTService = sonatypeRestService.value
      val s = streams.value
      val profiles =  rest.stagingProfiles
      if(profiles.isEmpty)
        s.log.warn(s"No staging profile is found for ${sonatypeProfileName.value}")
      else {
        s.log.info(s"Staging profiles (profileName:${sonatypeProfileName.value}):")
        s.log.info(profiles.mkString("\n"))
      }
      profiles
    },
    sonatypeList := {
      val rest : NexusRESTService = sonatypeRestService.value
      sonatypeStagingRepositoryProfiles.value
    },
    sonatypeClose := {
      val arg = repositoryIdParser.parsed
      val rest : NexusRESTService = sonatypeRestService.value
      val repo = rest.findTargetRepository(Close, arg)
      rest.closeStage(repo)
    },
    sonatypePromote := {
      val arg = repositoryIdParser.parsed
      val rest : NexusRESTService = sonatypeRestService.value
      val repo = rest.findTargetRepository(Promote, arg)
      rest.promoteStage(repo)
    },
    sonatypeDrop := {
      val arg = repositoryIdParser.parsed
      val rest : NexusRESTService = sonatypeRestService.value
      val repo = rest.findTargetRepository(Drop, arg)
      rest.dropStage(repo)
    },
    sonatypeRelease := {
      val arg = repositoryIdParser.parsed
      val rest : NexusRESTService = sonatypeRestService.value
      val repo = rest.findTargetRepository(CloseAndPromote, arg)
      rest.closeAndPromote(repo)
    },
    sonatypeReleaseAll := {
      val rest : NexusRESTService = sonatypeRestService.value
      val ret = for(repo <- rest.stagingRepositoryProfiles) yield {
        rest.closeAndPromote(repo)
      }
      ret.forall(_ == true)
    },
    sonatypeLog := {
      val s = streams.value
      val rest : NexusRESTService = sonatypeRestService.value
      val alist = rest.activities
      if(alist.isEmpty)
        s.log.warn("No staging log is found")
      for((repo, activities) <- alist) {
        s.log.info(s"Staging activities of $repo:")
        for(a <- activities) {
          a.log(s)
        }
      }
    }
  )


  /**
   * Switches of a Sonatype command to use
   */
  private sealed trait CommandType {
    def errNotFound : String
  }
  private case object Close extends CommandType {
    def errNotFound = "No open repository is found. Run publishSigned first"
  }
  private case object Promote extends CommandType {
    def errNotFound = "No closed repository is found. Run publishSigned and close commands"
  }
  private case object Drop extends CommandType {
    def errNotFound = "No staging repository is found. Run publishSigned first"
  }
  private case object CloseAndPromote extends CommandType {
    def errNotFound = "No staging repository is found. Run publishSigned first"
  }

  /**
   * Staging repository profile has an id of deployed artifact and the current staging state.
   * @param profileId
   * @param profileName
   * @param stagingType
   * @param repositoryId
   */
  case class StagingRepositoryProfile(profileId:String, profileName:String, stagingType:String, repositoryId:String) {
    override def toString = s"[$repositoryId] status:$stagingType, profile:$profileName($profileId)"
    def isOpen = stagingType == "open"
    def isClosed = stagingType == "closed"
    def isReleased = stagingType == "released"

    def toClosed = StagingRepositoryProfile(profileId, profileName, "closed", repositoryId)
    def toReleased = StagingRepositoryProfile(profileId, profileName, "released", repositoryId)
  }

  /**
   * Staging profile is the information associated to a Sonatype account.
   * @param profileId
   * @param profileName
   * @param repositoryTargetId
   */
  case class StagingProfile(profileId:String, profileName:String, repositoryTargetId:String)

  /**
   * Staging activity is an action to the staged repository
   * @param name activity name, e.g. open, close, promote, etc.
   * @param started
   * @param stopped
   * @param events
   */
  case class StagingActivity(name:String, started:String, stopped:String, events:Seq[ActivityEvent]) {
    override def toString = {
      val b = Seq.newBuilder[String]
      b += s"-activity -- name:$name, started:$started, stopped:$stopped"
      for(e <- events)
        b += s" ${e.toString}"
      b.result.mkString("\n")
    }

    def activityLog = s"Activity $name started:$started, stopped:$stopped"

    def log(s:TaskStreams) {
      s.log.info(activityLog)
      val hasError = containsError
      for(e <- suppressEvaluateLog) {
        e.log(s, hasError)
      }
    }

    def suppressEvaluateLog = {
      val in = events.toIndexedSeq
      var cursor = 0
      val b = Seq.newBuilder[ActivityEvent]
      while(cursor < in.size) {
        val current = in(cursor)
        if(cursor < in.size - 1) {
          val next = in(cursor+1)
          if(current.name == "ruleEvaluate" && current.ruleType == next.ruleType) {
            // skip
          }
          else {
            b += current
          }
        }
        cursor += 1
      }
      b.result
    }

    def containsError = events.exists(_.severity != "0")

    def reportFailure(s:TaskStreams) {
      s.log.error(activityLog)
      val failureReport = suppressEvaluateLog.filter(_.isFailure)
      for(e <- failureReport) {
        e.log(s, useErrorLog=true)
      }

    }

    def isReleaseSucceeded(repositoryId:String) : Boolean = {
      events
        .find(_.name == "repositoryReleased")
        .exists(_.property.getOrElse("id", "") == repositoryId)
    }

    def isCloseSucceeded(repositoryId:String) : Boolean = {
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
  case class ActivityEvent(timestamp:String, name:String, severity:String, property:Map[String, String]) {
    def ruleType : String = property.getOrElse("typeId", "other")
    def isFailure = name == "ruleFailed"

    override def toString = s"-event -- timestamp:$timestamp, name:$name, severity:$severity, ${property.map(p => s"${p._1}:${p._2}").mkString(", ")}"

    def log(s:TaskStreams, useErrorLog:Boolean = false) {
      val props = {
        val front = if(property.contains("typeId"))
          Seq(property("typeId"))
        else
          Seq.empty
        front ++ property.filter(_._1 != "typeId").map(p => s"${p._1}:${p._2}")
      }
      val messageLine = props.mkString(", ")
      val name_s = name.replaceAll("rule(s)?","")
      val message = f"$name_s%10s: $messageLine"
      if(useErrorLog)
        s.log.error(message)
      else
        s.log.info(message)
    }
  }

  class ActivityMonitor(s:TaskStreams) {
    var reportedActivities = Set.empty[String]
    var reportedEvents = Set.empty[ActivityEvent]

    def report(stagingActivities:Seq[StagingActivity]) = {
      for(sa <- stagingActivities) {
        if(!reportedActivities.contains(sa.started)) {
          s.log.info(sa.activityLog)
          reportedActivities += sa.started
        }
        for(ae <- sa.events if !reportedEvents.contains(ae)) {
          ae.log(s, useErrorLog = false)
          reportedEvents += ae
        }
      }
    }
  }

  /**
   * Interface to access the REST API of Nexus
   * @param s
   * @param repositoryUrl
   * @param profileName
   * @param cred
   * @param credentialHost
   */
  class NexusRESTService(s:TaskStreams,
                         repositoryUrl:String,
                         profileName:String,
                         cred:Seq[Credentials],
                         credentialHost:String) {

    val monitor = new ActivityMonitor(s)

    def findTargetRepository(command:CommandType, arg: Option[String]) : StagingRepositoryProfile = {
      val repos = command match {
        case Close => openRepositories
        case Promote => closedRepositories
        case Drop => stagingRepositoryProfiles
        case CloseAndPromote => stagingRepositoryProfiles
      }
      if(repos.isEmpty)
        throw new IllegalStateException(command.errNotFound)

      def findSpecifiedInArg(target: String) = {
        repos.find(_.repositoryId == target).getOrElse{
          s.log.error(s"Repository $target is not found")
          s.log.error(s"Specify one of the repository ids in:\n${repos.mkString("\n")}")
          throw new IllegalArgumentException(s"Repository $target is not found")
        }
      }

      arg.map(findSpecifiedInArg).getOrElse{
        if(repos.size > 1) {
          s.log.error(s"Multiple repositories are found:\n${repos.mkString("\n")}")
          s.log.error(s"Specify one of the repository ids in the command line")
          throw new IllegalStateException("Found multiple staging repositories")
        } else {
          repos.head
        }
      }
    }

    def openRepositories = stagingRepositoryProfiles.filter(_.isOpen).sortBy(_.repositoryId)
    def closedRepositories = stagingRepositoryProfiles.filter(_.isClosed).sortBy(_.repositoryId)

    private def repoBase(url:String) = if(url.endsWith("/")) url.dropRight(1) else url
    private val repo = {
      val url = repoBase(repositoryUrl)
      s.log.info(s"Nexus repository URL: $url")
      url
    }

    def Get[U](path:String)(body: HttpResponse => U) : U = {
      val req = new HttpGet(s"${repo}$path")
      req.addHeader("Content-Type", "application/xml")

      val retry = new ExponentialBackOffRetry(initialWaitSeq = 0)
      var toContinue = true
      var response : HttpResponse = null
      var ret : Any = null
      while(toContinue && retry.hasNext) {
        withHttpClient { client =>
          response = client.execute(req)
          s.log.debug(s"Status line: ${response.getStatusLine}")
          response.getStatusLine.getStatusCode match {
            case HttpStatus.SC_OK =>
              toContinue = false
              ret = body(response)
            case HttpStatus.SC_INTERNAL_SERVER_ERROR =>
              s.log.warn(s"Received 500 error: ${response.getStatusLine}")
              retry.doWait
            case _ =>
              throw new IOException(s"Failed to retrieve data from $path: ${response.getStatusLine}")
          }
        }
      }
      if(ret == null) {
        throw new IOException(s"Failed to retrieve data from $path")
      }
      ret.asInstanceOf[U]
    }

    def Post(path:String, bodyXML:String) = {
      val req = new HttpPost(s"${repo}$path")
      req.setEntity(new StringEntity(bodyXML))
      req.addHeader("Content-Type", "application/xml")

      val retry = new ExponentialBackOffRetry(initialWaitSeq = 0)
      var response : HttpResponse = null
      var toContinue = true
      while(toContinue && retry.hasNext) {
        withHttpClient { client =>
          response = client.execute(req)
          response.getStatusLine.getStatusCode match {
            case HttpStatus.SC_INTERNAL_SERVER_ERROR =>
              s.log.warn(s"Received 500 error: ${response.getStatusLine}")
              retry.doWait
            case _ =>
              s.log.debug(s"Status line: ${response.getStatusLine}")
              toContinue = false
          }
        }
      }
      if(toContinue) {
        throw new IOException(s"Failed to retrieve data from $path")
      }
      response
    }

    private def withHttpClient[U](body: HttpClient => U) : U = {
      val credt : DirectCredentials = Credentials.forHost(cred, credentialHost)
        .getOrElse {
          throw new IllegalStateException(s"No credential is found for $credentialHost. Prepare ~/.sbt/(sbt_version)/sonatype.sbt file.")
        }

      val client = new DefaultHttpClient()
      try {
        val user = credt.userName
        val passwd = credt.passwd
        client.getCredentialsProvider.setCredentials(
          new AuthScope(credt.host, AuthScope.ANY_PORT),
          new UsernamePasswordCredentials(user, passwd)
        )
        body(client)
      }
      finally
        client.getConnectionManager.shutdown()
    }


    def stagingRepositoryProfiles = {
      s.log.info("Reading staging repository profiles...")
      Get("/staging/profile_repositories") { response =>
        val profileRepositoriesXML = XML.load(response.getEntity.getContent)
        val repositoryProfiles = for(p <- profileRepositoriesXML \\ "stagingProfileRepository") yield {
          StagingRepositoryProfile(
            (p \ "profileId").text,
            (p \ "profileName").text,
            (p \ "type").text,
            (p \ "repositoryId").text)
        }
        val myProfiles = repositoryProfiles.filter(_.profileName == profileName)
        //s.log.info(s"Staging repository profiles:\n${myProfiles.mkString("\n")}")
        myProfiles
      }
    }

    def stagingProfiles = {
      s.log.info("Reading staging profiles...")
      Get("/staging/profiles") { response =>
        val profileXML = XML.load(response.getEntity.getContent)
        val profiles = for(p <- profileXML \\ "stagingProfile" if (p \ "name").text == profileName) yield {
          StagingProfile(
            (p \ "id").text,
            (p \ "name").text,
            (p \ "repositoryTargetId").text
          )
        }
        //s.log.info(s"Staging profiles:\n${profiles.mkString("\n")}")
        profiles
      }
    }

    lazy val currentProfile = {
      val profiles = stagingProfiles
      if(profiles.isEmpty) {
        throw new IllegalArgumentException(s"Profile ${profileName} is not found")
      }
      profiles.head
    }

    private def promoteRequestXML(repo:StagingRepositoryProfile) =
      s"""|<?xml version="1.0" encoding="UTF-8"?>
          |<promoteRequest>
          |  <data>
          |    <stagedRepositoryId>${repo.repositoryId}</stagedRepositoryId>
          |    <targetRepositoryId>${currentProfile.repositoryTargetId}</targetRepositoryId>
          |    <description>Requested by sbt-sonatype plugin</description>
          |  </data>
          |</promoteRequest>
         """.stripMargin


    class ExponentialBackOffRetry(initialWaitSeq:Int= 5, intervalSeq:Int=3, maxRetries:Int=10) {
      private var numTrial = 0
      private var currentInterval = intervalSeq

      def hasNext = numTrial < maxRetries

      def nextWait = {
        val interval = if(numTrial == 0) initialWaitSeq else currentInterval
        currentInterval = (currentInterval * 1.5 + 0.5).toInt
        numTrial += 1
        interval
      }

      def doWait  {
        val w = nextWait
        Thread.sleep(w * 1000)
      }

    }


    def closeStage(repo:StagingRepositoryProfile) = {
      var toContinue = true
      if(repo.isClosed || repo.isReleased) {
        s.log.info(s"Repository ${repo.repositoryId} is already closed")
        toContinue = false
      }

      if(toContinue) {
        // Post close request
        val postURL = s"/staging/profiles/${currentProfile.profileId}/finish"
        s.log.info(s"Closing staging repository $repo")
        val ret = Post(postURL, promoteRequestXML(repo))
        if(ret.getStatusLine.getStatusCode != HttpStatus.SC_CREATED) {
          throw new IOException(s"Failed to send close operation: ${ret.getStatusLine}")
        }
      }

      toContinue = true
      val timer = new ExponentialBackOffRetry()
      while(toContinue && timer.hasNext) {
        val activities = activitiesOf(repo)
        monitor.report(activities)
        activities.filter(_.name == "close").lastOption match {
          case Some(activity) =>
            if(activity.isCloseSucceeded(repo.repositoryId)) {
              toContinue = false
              s.log.info("Closed successfully")
            }
            else if(activity.containsError) {
              s.log.error("Failed to close the repository")
              activity.reportFailure(s)
              throw new Exception("Failed to close the repository")
            }
            else {
              // Activity log exists, but the close phase is not yet terminated
              s.log.debug("Close process is in progress ...")
              timer.doWait
            }
          case None =>
            timer.doWait
        }
      }
      if(toContinue)
        throw new IOException("Timed out")
      true
    }

    def dropStage(repo:StagingRepositoryProfile) = {
      val postURL = s"/staging/profiles/${currentProfile.profileId}/drop"
      s.log.info(s"Dropping staging repository $repo")
      val ret = Post(postURL, promoteRequestXML(repo))
      if(ret.getStatusLine.getStatusCode != HttpStatus.SC_CREATED) {
        throw new IOException(s"Failed to drop ${repo.repositoryId}: ${ret.getStatusLine}")
      }
      s.log.info(s"Dropped successfully: ${repo.repositoryId}")
      true
    }


    def promoteStage(repo:StagingRepositoryProfile) = {
      var toContinue = true
      if(repo.isReleased) {
        s.log.info(s"Repository ${repo.repositoryId} is already released")
        toContinue = false
      }

      if(toContinue) {
        // Post promote(release) request
        val postURL = s"/staging/profiles/${currentProfile.profileId}/promote"
        s.log.info(s"Promoting staging repository $repo")
        val ret = Post(postURL, promoteRequestXML(repo))
        if(ret.getStatusLine.getStatusCode != HttpStatus.SC_CREATED) {
          s.log.error(s"${ret.getStatusLine}")
          for(errorLine <- Source.fromInputStream(ret.getEntity.getContent).getLines()) {
            s.log.error(errorLine)
          }
          throw new Exception("Failed to promote the repository")
        }
      }

      toContinue = true
      val timer = new ExponentialBackOffRetry()
      while(toContinue && timer.hasNext) {
        val activities = activitiesOf(repo)
        monitor.report(activities)
        activities.filter(_.name == "release").lastOption match {
          case Some(activity) =>
            if(activity.isReleaseSucceeded(repo.repositoryId)) {
              s.log.info("Promoted successfully")

              // Drop after release
              dropStage(repo.toReleased)
              toContinue = false
            }
            else if(activity.containsError) {
              s.log.error("Failed to promote the repository")
              activity.reportFailure(s)
              throw new Exception("Failed to promote the repository")
            }
            else {
              s.log.debug("Release process is in progress ...")
              timer.doWait
            }
          case None =>
            timer.doWait
        }
      }
      if(toContinue)
        throw new IOException("Timed out")
      true

    }

    def stagingRepositoryInfo(repositoryId:String) = {
      s.log.info(s"Seaching for repository $repositoryId ...")
      val ret = Get(s"/staging/repository/$repositoryId") { response =>
        XML.load(response.getEntity.getContent)
      }
    }

    def closeAndPromote(repo:StagingRepositoryProfile) : Boolean = {
      if(repo.isReleased) {
        dropStage(repo)
      }
      else {
        closeStage(repo)
        promoteStage(repo.toClosed)
      }
      true
    }

    def activities : Seq[(StagingRepositoryProfile, Seq[StagingActivity])] = {
      for(r <- stagingRepositoryProfiles) yield
        r -> activitiesOf(r)
    }

    def activitiesOf(r:StagingRepositoryProfile) : Seq[StagingActivity] =  {
      s.log.debug(s"Checking activity logs of ${r.repositoryId} ...")
      val a = Get(s"/staging/repository/${r.repositoryId}/activity") { response =>
        val xml = XML.load(response.getEntity.getContent)
        for(sa <- xml \\ "stagingActivity") yield {
          val ae = for(event <- sa \ "events" \ "stagingActivityEvent") yield {
            val props = for(prop <- event \ "properties" \ "stagingProperty") yield {
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


}

