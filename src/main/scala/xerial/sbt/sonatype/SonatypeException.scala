package xerial.sbt.sonatype

/** An exception used for showing only an error message when there is no need to show stack traces
  */
case class SonatypeException(errorCode: ErrorCode, message: String) extends Exception(message) {
  override def toString = s"[${errorCode}] ${message}"
}

sealed trait ErrorCode

object SonatypeException {

  case object STAGE_IN_PROGRESS extends ErrorCode

  case object STAGE_FAILURE extends ErrorCode

  case object BUNDLE_UPLOAD_FAILURE extends ErrorCode

  case object MISSING_CREDENTIAL extends ErrorCode

  case object MISSING_STAGING_PROFILE extends ErrorCode

  case object MISSING_PROFILE extends ErrorCode

  case object UNKNOWN_STAGE extends ErrorCode

  case object MULTIPLE_TARGETS extends ErrorCode

}
