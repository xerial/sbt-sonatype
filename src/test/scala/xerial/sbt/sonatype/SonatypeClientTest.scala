package xerial.sbt.sonatype;

import wvlet.airframe.codec.{JSONCodec, MessageCodec}
import wvlet.airframe.msgpack.spi.MessagePack
import wvlet.airspec.AirSpec
import wvlet.log.io.IOUtil
import xerial.sbt.Sonatype
import xerial.sbt.sonatype.SonatypeClient.StagingProfileResponse;

/**
  */
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
  test("topLevelGroupId com.company.project.product = com.company") {
    val groupId  = "com.company.project.product"
    val expected = "com.company"
    val actual   = Sonatype.topLevelGroupId(groupId)
    actual shouldBe expected
  }
  test("topLevelGroupId io.gitlab.team.project = io.gitlab.team") {
    val groupId  = "io.gitlab.team.project"
    val expected = "io.gitlab.team"
    val actual   = Sonatype.topLevelGroupId(groupId)
    actual shouldBe expected
  }
}
