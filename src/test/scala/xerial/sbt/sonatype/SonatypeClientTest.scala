package xerial.sbt.sonatype;

import wvlet.airframe.codec.{JSONCodec, MessageCodec}
import wvlet.airframe.msgpack.spi.MessagePack
import wvlet.airspec.AirSpec
import wvlet.log.io.IOUtil
import xerial.sbt.sonatype.SonatypeClient.StagingProfileResponse;

/** */
class SonatypeClientTest extends AirSpec {
  test("parse profile json") {
    val json    = IOUtil.readAsString("/profiles.json")
    val codec   = MessageCodec.of[StagingProfileResponse]
    val profile = codec.fromJson(json)
    profile.data.size shouldBe 2

    val p = MessagePack.newBufferPacker
    JSONCodec.pack(p, json)
    val msgpack = p.toByteArray

    val unpacked = codec.fromMsgPack(msgpack)
    unpacked.data.size shouldBe 2

    profile shouldBe unpacked
  }

  test("build client") {
    val client = new SonatypeClient("https://oss.sonatype.org/service/local/", Seq.empty, "")
    client.httpClient
  }

//  test("create client") {
//    val client = new SonatypeClient("https://httpbin.org/", Seq.empty, "")
//    client.httpClient.readAs[Json](Http.GET("/status/500"))
//  }
}
