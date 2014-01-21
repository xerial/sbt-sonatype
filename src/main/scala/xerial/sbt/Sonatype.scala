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
object Sonatype extends sbt.Plugin {

  object SonatypeKeys {
    val repository = settingKey[String]("Sonatype repository URL")
    val profileName = settingKey[String]("profile name at Sonatype: e.g. org.xerial")
    val close = taskKey[Boolean]("Close the stage")
    val promote = taskKey[Boolean]("close and promoe the repository")

    val credentialHost = settingKey[String]("Credential host e.g. oss.sonatype.org")
    val restService = taskKey[NexusRESTService]("REST API")

    val list = taskKey[Unit]("List staging repositories")
    val stagingActivities = taskKey[Unit]("Show repository activities")
    val stagingRepositoryProfiles = taskKey[Seq[StagingRepositoryProfile]]("List staging repository profiles")
    val stagingProfiles = taskKey[Seq[StagingProfile]]("List staging profiles")
    val closeAndPromote = taskKey[Boolean]("Publish to Maven central via close and promote")

  }



  import SonatypeKeys._
  lazy val sonatypeSettings = Seq[Def.Setting[_]](
    // Add sonatyep repository settings
    publishTo <<= version { (v) => Some(releaseResolver(v)) },
    publishMavenStyle := true,
    repository := "https://oss.sonatype.org/service/local",
    credentialHost := "oss.sonatype.org",
    profileName := organization.value,
    restService := new NexusRESTService(streams.value, repository.value, profileName.value, credentials.value, credentialHost.value),
    stagingRepositoryProfiles := {
      val rest : NexusRESTService = restService.value
      val s = streams.value
      val repos = rest.stagingRepositoryProfiles
      s.log.info("Staging repository profiles:")
      s.log.info(repos.mkString("\n"))
      repos
    },
    stagingProfiles := {
      val rest : NexusRESTService = restService.value
      val s = streams.value
      val profiles =  rest.stagingProfiles
      s.log.info("Staging profiles:")
      s.log.info(profiles.mkString("\n"))
      profiles
    },
    list := {
      val rest : NexusRESTService = restService.value
      stagingRepositoryProfiles.value
    },
    close := {
      val rest : NexusRESTService = restService.value
      rest.findTargetRepository(isPromote=false) match {
        case Some(r) =>
          rest.closeStage(r)
        case None =>
          false
      }
    },
    promote := {
      val rest : NexusRESTService = restService.value
      rest.findTargetRepository(isPromote=true) match {
        case Some(r) =>
          rest.promoteStage(r)
        case None =>
          false
      }
    },
    closeAndPromote := {
      val rest : NexusRESTService = restService.value

      false
    },
    stagingActivities := {
      val s = streams.value
      val rest : NexusRESTService = restService.value
      for((repo, activities) <- rest.activities) {
        s.log.info(s"Staging activities of $repo:")
        for(a <- activities) {
          a.log(s)
        }
      }
    }

  )


  def releaseResolver(v: String): Resolver = {
    val nexus = "https://oss.sonatype.org/"
    if (v.trim.endsWith("SNAPSHOT"))
      "snapshots" at nexus + "content/repositories/snapshots"
    else
      "releases" at nexus + "service/local/staging/deploy/maven2"
  }


  case class StagingRepositoryProfile(profileId:String, profileName:String, stagingType:String, repositoryId:String) {
    override def toString = s"[$repositoryId] status:$stagingType, profile:$profileName($profileId)"
    def isOpen = stagingType == "open"
    def isClosed = stagingType == "closed"
    def isReleased = stagingType == "released"
  }
  case class StagingProfile(profileId:String, profileName:String, repositoryTargetId:String)

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

    def isReleaseSucceeded : Boolean = {
      events.find(_.name == "repositoryReleased").isDefined
    }

    def isCloseSucceeded(repositoryId:String) : Boolean = {
      events
        .find(_.name == "repositoryClosed")
        .find(log => log.property.contains("id") && log.property("id") == repositoryId).isDefined
    }

  }

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

  class NexusRESTService(s:TaskStreams,
                         repositoryUrl:String,
                         profileName:String,
                         cred:Seq[Credentials],
                         credentialHost:String) {

    def findTargetRepository(isPromote:Boolean) = {
      val repos = if(isPromote) closedRepositories else openRepositories

      if(repos.isEmpty) {
        val err = if(isPromote)
          "No closed repository is found. Run publish-signed and close commands."
        else
          "No staging repository is found. Run publish-signed first."

        s.log.error(err)
        None
      }
      else if(repos.size > 1) {
        val label = repos.zipWithIndex.map{case (r, i) => s"[${r.repositoryId}] $r"}
        val err = if(isPromote)
          "Specify a repository id via promote (repository id)"
          else
          "Specify a repository id via close (respotiory id)"

        s.log.warn(s"Multiple repositories are found: ${label.mkString(", ")}")
        s.log.warn(err)
        None
      }
      else {
        repos.headOption
      }
    }


    def openRepositories = stagingRepositoryProfiles.filter(_.isOpen).sortBy(_.repositoryId)
    def closedRepositories = stagingRepositoryProfiles.filter(_.isClosed).sortBy(_.repositoryId)

    private def repoBase(url:String) = if(url.endsWith("/")) url.dropRight(1) else url
    private val repo = {
      val url = repoBase(repositoryUrl)
      s.log.info(s"Nexus repository: $url")
      url
    }

    def Get[U](path:String)(body: HttpResponse => U) : U = {
      val req = new HttpGet(s"${repo}$path")
      req.addHeader("Content-Type", "application/xml")
      withHttpClient{ client =>
        val response = client.execute(req)
        s.log.debug(s"Status line: ${response.getStatusLine}")
        if(response.getStatusLine.getStatusCode != HttpStatus.SC_OK) {
          throw new IOException(s"Failed to retrieve data from $path: ${response.getStatusLine}")
        }
        body(response)
      }
    }

    def Post(path:String, bodyXML:String) = {
      val req = new HttpPost(s"${repo}$path")
      req.setEntity(new StringEntity(bodyXML))
      req.addHeader("Content-Type", "application/xml")
      withHttpClient{ client =>
        val response = client.execute(req)
        s.log.debug(s"Status line: ${response.getStatusLine}")
        response
      }
    }


    private def withHttpClient[U](body: HttpClient => U) : U = {
      val credt : Option[DirectCredentials] = cred.collectFirst{ case d:DirectCredentials if d.host == credentialHost => d}
      if(credt.isEmpty)
        throw new IllegalStateException(s"No credential is found for ${credentialHost}. Prepare ~/.sbt/(sbt_version)/sonatype.sbt file.")

      val client = new DefaultHttpClient()
      try {
        val user = credt.get.userName
        val passwd = credt.get.passwd
        client.getCredentialsProvider.setCredentials(
          new AuthScope(credt.get.host, AuthScope.ANY_PORT),
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
      if(profiles.isEmpty)
        throw new IllegalArgumentException(s"Profile ${profileName} is not found")
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


    class ExponentialBackOffRetry(initialWaitSeq:Int= 1, intervalSeq:Int=3, maxRetries:Int=5) {
      private var numTrial = 0
      private val currentWait = initialWaitSeq
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
      val timer = new ExponentialBackOffRetry()
      while(toContinue && timer.hasNext) {
        activitiesOf(repo).filter(_.name == "close").lastOption match {
          case None =>
            // Post close request
            s.log.info(s"Closing staging repository $repo")
            val ret = Post(s"/staging/profiles/${currentProfile.profileId}/finish", promoteRequestXML(repo))
            if(ret.getStatusLine.getStatusCode != HttpStatus.SC_CREATED) {
              throw new IOException(s"Failed to send close operation: ${ret.getStatusLine}")
            }
          case Some(activity) =>
            if(activity.isCloseSucceeded(repo.repositoryId)) {
              toContinue = false
              s.log.error("Closed successfully")
            }
            else if(activity.containsError) {
              s.log.error("Failed to close the repository")
              activity.reportFailure(s)
              throw new Exception("Failed to close the repository")
            }
            else {
              // Activity log exists, but the close phase is not yet terminated
              timer.doWait
            }
        }
      }
      if(toContinue == true)
        throw new IOException("Timed out")

      true
    }

    def dropStage(repo:StagingRepositoryProfile) = {
      s.log.info(s"Closing staging repository $repo")
      val ret = Post(s"/staging/profiles/${currentProfile.profileId}/drop", promoteRequestXML(repo))
      ret.getStatusLine.getStatusCode == HttpStatus.SC_CREATED
    }


    def promoteStage(repo:StagingRepositoryProfile) = {
      s.log.info(s"Promoting staging repository $repo")
      val ret = Post(s"/staging/profiles/${currentProfile.profileId}/promote", promoteRequestXML(repo))
      ret.getStatusLine.getStatusCode match {
        case HttpStatus.SC_CREATED => true
        case other =>
          s.log.error(s"${ret.getStatusLine}")
          for(errorLine <- Source.fromInputStream(ret.getEntity.getContent).getLines()) {
            s.log.error(errorLine)
          }
          false
      }
    }

    def stagingRepositoryInfo(repositoryId:String) = {
      s.log.info(s"Seaching for repository $repositoryId ...")
      val ret = Get(s"/staging/repository/${repositoryId}") { response =>
        XML.load(response.getEntity.getContent)
      }
    }

    def closeAndPromote : Boolean = {



      true
    }

    def activities : Seq[(StagingRepositoryProfile, Seq[StagingActivity])] = {
      for(r <- stagingRepositoryProfiles) yield
        r -> activitiesOf(r)
    }

    def activitiesOf(r:StagingRepositoryProfile) =  {
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

