package xerial.sbt.sonatype

import xerial.sbt.Sonatype

/** An exception used for showing only an error message when there is no need to show stack traces
  */
case class SonatypeException(errorCode: ErrorCode, message: String) extends Exception(message) {
  override def toString = s"[${errorCode}] ${message}"
}

sealed trait ErrorCode

object SonatypeException {

  case object AUTH_ERROR extends ErrorCode

  case object BUNDLE_ZIP_ERROR extends ErrorCode

  case object GENERIC_ERROR extends ErrorCode

  case object JSON_PARSING_ERROR extends ErrorCode

  case object STAGE_IN_PROGRESS extends ErrorCode

  case object STAGE_FAILURE extends ErrorCode

  case object STATUS_CHECK_FAILURE extends ErrorCode

  case object BUNDLE_UPLOAD_FAILURE extends ErrorCode

  case object MISSING_CREDENTIAL extends ErrorCode

  case object MISSING_STAGING_PROFILE extends ErrorCode

  case class MISSING_PROFILE(profileName: String, host: String) extends ErrorCode {
    def problem                               = s"Profile ${profileName} is not found on ${host}"
    def possibleAlternativeHosts: Seq[String] = Sonatype.knownOssHosts.filterNot(_ == host)
    def hostAdvice                            = s"try ${possibleAlternativeHosts.mkString(", or ")}?"
    def advice: String =
      s"In your sbt settings, check your sonatypeProfileName and sonatypeCredentialHost ($hostAdvice)"

    def message: String = s"${problem}. ${advice}"
  }

  case object UNKNOWN_STAGE extends ErrorCode

  case object MULTIPLE_TARGETS extends ErrorCode

}
