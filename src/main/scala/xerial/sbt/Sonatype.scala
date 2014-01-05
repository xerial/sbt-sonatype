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
import java.io.ByteArrayOutputStream
import scala.xml.XML
import org.apache.http.client.HttpClient


/**
 * Plugin for automating releases at Sonatype 
 * @author Taro L. Saito
 */
object Sonatype extends sbt.Plugin {



  object SonatypeKeys {
    val repository = settingKey[String]("Sonatype repository URL")
    val profile = settingKey[String]("profile name at Sonatype: e.g. org.xerial")
    val close = taskKey[Boolean]("Close the stage")
    val promote = taskKey[Boolean]("close and promoe the repositoy")
    val list = taskKey[Unit]("list staging repositories")
    val credentialHost = settingKey[String]("Credential host e.g. oss.sonatype.org")
  }

  import SonatypeKeys._ 
  lazy val sonatypeSettings = Seq[Def.Setting[_]](
    repository := "https://oss.sonatype.org/service/local",
    credentialHost := "oss.sonatype.org",
    profile := organization.value,
    list := {
      val rest = new NexusRESTService(streams.value, repository.value, profile.value, credentials.value, credentialHost.value)
      rest.stagingProfiles
    },
    close := {
      true
    },
    promote := {
      true
    }
  )



  case class StagingProfile(profileId:String, profileName:String, stagingType:String, repositoryId:String)




  class NexusRESTService(s:TaskStreams, repositoryUrl:String, profileName:String, cred:Seq[Credentials], credentialHost:String) {

    
    private def repoBase(url:String) = if(url.endsWith("/")) url.dropRight(1) else url
    private val repo = {
      val url = repoBase(repositoryUrl)
      s.log.info(s"Nexus repository: $url")
      url
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

    def stagingProfiles = {
      s.log.info("Listing staging repository...")
      val req = new HttpGet(s"${repo}/staging/profile_repositories")
      req.addHeader("Content-Type", "application/xml")

      withHttpClient { client =>
        val response = client.execute(req)
        s.log.info(s"Status line: ${response.getStatusLine}")
        val profileRepositoriesXML = XML.load(response.getEntity.getContent)
        val profiles = for(p <- profileRepositoriesXML \\ "stagingProfileRepository") yield {
          StagingProfile(
            (p \ "profileId").text,
            (p \ "profileName").text,
            (p \ "type").text,
            (p \ "repositoryId").text)
        }
        s.log.info(s"profiles:\n${profiles.mkString("\n")}")
      }
    }



  }



}

