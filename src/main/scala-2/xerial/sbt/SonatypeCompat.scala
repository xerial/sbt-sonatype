package xerial.sbt.sonatype

import scala.reflect.runtime.universe.TypeTag
import wvlet.airframe.codec.MessageCodec
import wvlet.airframe.codec.MessageCodecFactory

private[sonatype] trait SonatypeCompat { self: SonatypeService =>
  implicit def codecInstance[A: TypeTag]: MessageCodec[A] =
    MessageCodecFactory.defaultFactoryForJSON.of[A]
}
