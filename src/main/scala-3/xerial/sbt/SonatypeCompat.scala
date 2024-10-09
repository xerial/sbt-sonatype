package xerial.sbt.sonatype

import wvlet.airframe.codec.MessageCodec
import wvlet.airframe.codec.MessageCodecFactory

private[sonatype] trait SonatypeCompat { self: SonatypeService =>
  inline given codecInstance[A]: MessageCodec[A] =
    MessageCodecFactory.defaultFactoryForJSON.of[A]
}
