//--------------------------------------
//
// Sonatype$Test.scala
// Since: 2014/01/05 22:05
// 
//--------------------------------------

package xerial.sbt

import org.scalatest.{WordSpec, FunSuite}
import org.apache.http.impl.client.{HttpClients, HttpClientBuilder}

/**
 * @author Taro L. Saito
 */
class SonatypeTest extends WordSpec {
  "SonatypePlugin" should {
    "connect sonatype REST API" in {
      val client = HttpClients.createDefault()

    }
  }
}