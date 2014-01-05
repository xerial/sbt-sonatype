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
      val s = streams.value
      val repo = repoBase(repository.value)
      s.log.info(s"Nexus repository: $repo")
      s.log.info("Listing staging repository...")


      val credt : Option[DirectCredentials] = credentials.value.collectFirst{ case d:DirectCredentials if d.host == credentialHost.value => d}
      if(credt.isEmpty) {
        s.log.error(s"No credential is found for ${credentialHost.value}")
        false
      }

      val user = credt.get.userName
      val passwd = credt.get.passwd

      val client = new DefaultHttpClient()
      try {
        client.getCredentialsProvider.setCredentials(
          new AuthScope(credt.get.host, AuthScope.ANY_PORT),
          new UsernamePasswordCredentials(user, passwd)
        )

        val req = new HttpGet(s"${repo}/staging/profile_repositories")
        req.addHeader("Content-Type", "application/xml")
        val response = client.execute(req)
        s.log.info(s"Status line: ${response.getStatusLine}")
        val entity = response.getEntity
        val len = entity.getContentLength
        val b = new ByteArrayOutputStream()
        entity.writeTo(b)
        val content = b.toString("UTF-8")
        s.log.info(content)
        response.toString
      }
      finally
        client.getConnectionManager.shutdown()
    },
    close := {
      true
    },
    promote := {
      true
    }
  )

  private def repoBase(url:String) = if(url.endsWith("/")) url.dropRight(1) else url

}

