package xerial.sbt.sonatype

/**
  *
  */
case class SonatypeException(errorCode: ErrorCode, message: String) extends Exception(message) {
  override def toString = s"[${errorCode}] ${message}"
}

sealed trait ErrorCode

object SonatypeException {

  case object STAGE_IN_PROGRESS     extends ErrorCode
  case object STAGE_FAILURE         extends ErrorCode
  case object BUNDLE_ALREADY_EXISTS extends ErrorCode
}
