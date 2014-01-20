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
import org.apache.http.client.methods.HttpGet
import scala.xml.XML
import org.apache.http.client.HttpClient
import org.apache.http.HttpResponse


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
    val stagingRepositoryProfiles = taskKey[Unit]("List staging repository profiles")
    val stagingProfiles = taskKey[Unit]("List staging profiles")
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
      rest.stagingRepositoryProfiles
    },
    list := {
      val rest : NexusRESTService = restService.value
      rest.stagingRepositoryProfiles
      rest.stagingProfiles
    },
    close := {
      val rest : NexusRESTService = restService.value

      true
    },
    promote := {
      true
    }
  )



  case class StagingRepositoryProfile(profileId:String, profileName:String, stagingType:String, repositoryId:String)
  case class StagingProfile(profileId:String, profileName:String, repositoryTargetId:String)


  class NexusRESTService(s:TaskStreams,
                         repositoryUrl:String,
                         profileName:String,
                         cred:Seq[Credentials],
                         credentialHost:String) {

    
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
        s.log.info(s"Status line: ${response.getStatusLine}")
        body(response)
      }
    }

    def withHttpClient[U](body: HttpClient => U) : U = {
      val credt : Option[DirectCredentials] = cred.collectFirst{ case d:DirectCredentials if d.host == credentialHost => d}
      if(credt.isEmpty)
        throw new IllegalStateException(s"No credential is found for ${credentialHost}")

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
        s.log.info(s"my staging repository profiles:\n${myProfiles.mkString("\n")}")
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
        s.log.info(s"my staging profiles:\n${profiles.mkString("\n")}")
      }
    }

    def closeStage = {
      s.log.info("Closing staging repository...")



    }


  }



}

