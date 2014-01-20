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


/**
 * Plugin for automating release processes at Sonatype Nexus
 * @author Taro L. Saito
 */
object Sonatype extends sbt.Plugin {

  object SonatypeKeys {
    val repository = settingKey[String]("Sonatype repository URL")
    val profile = settingKey[String]("profile name at Sonatype: e.g. org.xerial")
    val close = taskKey[Boolean]("Close the stage")
    val promote = taskKey[Boolean]("close and promoe the repository")

    val credentialHost = settingKey[String]("Credential host e.g. oss.sonatype.org")
    val restService = taskKey[NexusRESTService]("REST API")

    val list = taskKey[Unit]("list staging repositories")
    val stagingRepositoryProfiles = taskKey[Seq[StagingRepositoryProfile]]("List staging repository profiles")
    val stagingProfiles = taskKey[Seq[StagingProfile]]("List staging profiles")
  }

  import SonatypeKeys._ 
  lazy val sonatypeSettings = Seq[Def.Setting[_]](
    repository := "https://oss.sonatype.org/service/local",
    credentialHost := "oss.sonatype.org",
    profile := organization.value,
    restService := new NexusRESTService(streams.value, repository.value, profile.value, credentials.value, credentialHost.value),
    stagingRepositoryProfiles := {
      val rest : NexusRESTService = restService.value
      rest.stagingRepositoryProfiles
    },
    stagingProfiles := {
      val rest : NexusRESTService = restService.value
      rest.stagingProfiles
    },
    list := {
      val rest : NexusRESTService = restService.value
      rest.stagingRepositoryProfiles
    },
    close := {
      val s = streams.value
      val rest : NexusRESTService = restService.value
      val openRepos = rest.openRepositories
      if(openRepos.isEmpty) {
        s.log.warn("No staging repository is found. Run publish-signed first")
        false
      }
      else if(openRepos.size > 1) {
        val label = openRepos.zipWithIndex.map{case (r, i) => s"[$i] $r"}
        s.log.warn(s"Multiple repositories are found: ${label.mkString(", ")}")
        s.log.warn("Run close (repository number)")
        false
      }
      else {
        val repo = openRepos.head
        val p = stagingProfiles.value.head
        rest.closeStage(p, repo)
      }
    },
    promote := {
      val s = streams.value
      val rest : NexusRESTService = restService.value
      val closedRepos = rest.closedRepositories
      if(closedRepos.isEmpty) {
        s.log.warn("No closed repository is found. Run publish-signed followed, then close")
        false
      }
      else if(closedRepos.size > 1) {
        val label = closedRepos.zipWithIndex.map{case (r, i) => s"[$i] $r"}
        s.log.warn(s"Multiple repositories are found: ${label.mkString(", ")}")
        s.log.warn("Run promote (repository number)")
        false
      }
      else {
        val repo = closedRepos.head
        val p = stagingProfiles.value.head
        rest.promoteStage(p, repo)
      }
    }
  )



  case class StagingRepositoryProfile(profileId:String, profileName:String, stagingType:String, repositoryId:String) {
    override def toString = s"status:$stagingType, repository:$repositoryId, profile:$profileName($profileId)"
    def isOpen = stagingType == "open"
    def isClosed = stagingType == "closed"
  }
  case class StagingProfile(profileId:String, profileName:String, repositoryTargetId:String)


  class NexusRESTService(s:TaskStreams,
                         repositoryUrl:String,
                         profileName:String,
                         cred:Seq[Credentials],
                         credentialHost:String) {


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
        body(response)
      }
    }

    def Post(path:String, bodyXML:String) = {
      val req = new HttpPost(s"${repo}$path")
      req.setEntity(new StringEntity(bodyXML))
      req.addHeader("Content-Type", "application/xml")
      withHttpClient{ client =>
        val response = client.execute(req)
        s.log.info(s"Status line: ${response.getStatusLine}")
        response
      }

    }


    def withHttpClient[U](body: HttpClient => U) : U = {
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
      s.log.info("Listing staging repository profiles...")
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
        s.log.info(s"Staging repository profiles:\n${myProfiles.mkString("\n")}")
        myProfiles
      }
    }

    def stagingProfiles = {
      s.log.info("Listing staging profiles...")
      Get("/staging/profiles") { response =>
        val profileXML = XML.load(response.getEntity.getContent)
        val profiles = for(p <- profileXML \\ "stagingProfile" if (p \ "name").text == profileName) yield {
          StagingProfile(
            (p \ "id").text,
            (p \ "name").text,
            (p \ "repositoryTargetId").text
          )
        }
        s.log.info(s"Staging profiles:\n${profiles.mkString("\n")}")
        profiles
      }
    }

    def closeStage(profile:StagingProfile, repo:StagingRepositoryProfile) = {
      s.log.info(s"Closing staging repository $repo")
      val ret = Post(s"/staging/profiles/${profile.profileId}/finish",
        s"""<?xml version="1.0" encoding="UTF-8"?>
          <promoteRequest>
            <data>
              <stagedRepositoryId>${repo.repositoryId}</stagedRepositoryId>
              <targetRepositoryId>${profile.repositoryTargetId}</targetRepositoryId>
              <description>Closing</description>
            </data>
          </promoteRequest>
         """
      )
      ret.getStatusLine.getStatusCode == HttpStatus.SC_CREATED
    }

    def promoteStage(profile:StagingProfile, repo:StagingRepositoryProfile) = {
      s.log.info(s"Promoting staging repository $repo")
      val ret = Post(s"/staging/profiles/${profile.profileId}/promote",
        s"""<?xml version="1.0" encoding="UTF-8"?>
          <promoteRequest>
            <data>
              <description>Promoted</description>
              <stagedRepositoryId>${repo.repositoryId}</stagedRepositoryId>
              <targetRepositoryId>${profile.repositoryTargetId}</targetRepositoryId>
            </data>
          </promoteRequest>
         """
      )
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
  }


}

