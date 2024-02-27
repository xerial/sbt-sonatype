package xerial.sbt.sonatype

import io.circe.Decoder
import io.circe.generic.semiauto.*
import sbt.librarymanagement.ivy.Credentials
import sttp.client4.circe.asJson
import sttp.client4.httpurlconnection.HttpURLConnectionBackend
import sttp.client4.logging.slf4j.Slf4jLoggingBackend
import sttp.client4.quick.quickRequest
import sttp.client4.{Request, Response, ResponseException, SyncBackend, UriContext, multipartFile}
import sttp.model.HeaderNames
import wvlet.log.LogSupport
import xerial.sbt.sonatype.SonatypeCentralClient.{DeploymentId, DeploymentState, PublishingType, StatusCheckResponseBody}
import xerial.sbt.sonatype.SonatypeException.{BUNDLE_UPLOAD_FAILURE, JSON_PARSING_ERROR, STATUS_CHECK_FAILURE}
import xerial.sbt.sonatype.utils.Extensions.*

import java.io.File
import scala.concurrent.duration.DurationInt
import scala.math.pow
import scala.util.Try

private[sbt] class SonatypeCentralClient(
    backend: SyncBackend,
    sonatypeCredentials: SonatypeCredentials,
    timeoutMillis: Int = 3 * 60 * 60 * 1000
) extends AutoCloseable
    with LogSupport {

  private lazy val base64Credentials = sonatypeCredentials.toBase64

  private lazy val clientUrl = s"https://${SonatypeCentralClient.host}/api/v1/publisher"

  private def sendRequest[T](errorCode: ErrorCode)(request: Request[T]): Either[SonatypeException, Response[T]] =
    Try(backend.send(request)).toEither.leftMap { err =>
      info(s"Failure: ${err.getMessage}")
      SonatypeException(errorCode, err.getMessage)
    }

  private def parseJsonBody[A](
      responseBody: Either[ResponseException[String, io.circe.Error], A]
  ): Either[SonatypeException, A] = responseBody.leftMap { responseException =>
    SonatypeException(JSON_PARSING_ERROR, responseException.getMessage)
  }

  private def retryRequest[A](
      request: Request[A],
      successCode: Int,
      errorCode: ErrorCode,
      retriesLeft: Int,
      retriesAttempted: Int = 0
  ): Either[SonatypeException, A] = {

    for {
      res <- sendRequest(errorCode)(request)
      finalRes <-
        if (res.code.code != successCode) {
          if (retriesLeft > 0) {
            val exponent                   = pow(5, retriesAttempted).toInt
            val maximum                    = 30000
            val initialMillisecondsToSleep = 1500 + exponent
            val finalMillisecondsToSleep = if (maximum < initialMillisecondsToSleep) {
              maximum
            } else initialMillisecondsToSleep
            Thread.sleep(finalMillisecondsToSleep)
            retryRequest(request, successCode, errorCode, retriesLeft - 1, retriesAttempted + 1)
          } else {
            Left(SonatypeException(errorCode, s" Received: ${res.code.code}. Message: ${res.body}"))
          }
        } else {
          Right(res.body)
        }
    } yield finalRes
  }

  private def authorizationHeader: (String, String) = (HeaderNames.Authorization, s"UserToken $base64Credentials")

  override def close(): Unit = {
    backend.close()
  }

  def uploadBundle(
      localBundlePath: File,
      deploymentName: DeploymentName,
      publishingType: Option[PublishingType]
  ): Either[SonatypeException, DeploymentId] = {
    val endpoint = uri"$clientUrl/upload"
      .addParam("publishingType", publishingType.map(_.unapply))
      .addParam("name", deploymentName.unapply)

    info(s"Uploading bundle ${localBundlePath.getPath} to $endpoint")

    val request = quickRequest
      .post(endpoint)
      .logSettings(logResponseBody = Some(true), logRequestBody = Some(true))
      .readTimeout(timeoutMillis.milliseconds)
      .headers(
        Map(authorizationHeader)
      )
      .contentType("multipart/form-data")
      .multipartBody(
        multipartFile(
          "bundle",
          localBundlePath
        )
      )
    val res = retryRequest(request, 201, BUNDLE_UPLOAD_FAILURE, 60)

    res.map(DeploymentId)
  }

  def didDeploySucceed(deploymentId: DeploymentId): Either[SonatypeException, Boolean] = {
    val finalEndpoint = uri"$clientUrl/status".addParam("id", deploymentId.unapply)

    for {
      res <- sendRequest(STATUS_CHECK_FAILURE) {
        quickRequest
          .post(finalEndpoint)
          .logSettings(logResponseBody = Some(true))
          .headers(
            Map(authorizationHeader)
          )
          .response(asJson[StatusCheckResponseBody])
      }
      responseBody <- parseJsonBody(res.body)
      finalRes <-
        if (
          responseBody.deploymentState == DeploymentState.VALIDATING || responseBody.deploymentState == DeploymentState.PENDING
        ) {
          Thread.sleep(5000L)
          didDeploySucceed(deploymentId)
        } else if (responseBody.deploymentState == DeploymentState.FAILED) {
          error(
            s"Deployment failed for deployment id: ${deploymentId.unapply}. Current deployment state: ${responseBody.deploymentState.unapply}"
          )
          Right(false)
        } else {
          info(
            s"Deployment succeeded for deployment id: ${deploymentId.unapply}. Current deployment state: ${responseBody.deploymentState.unapply}"
          )
          Right(true)
        }
    } yield finalRes
  }
}

object SonatypeCentralClient {
  val host = "central.sonatype.com"

  def fromCredentials(credentials: Seq[Credentials]): Either[SonatypeException, SonatypeCentralClient] =
    for {
      sonatypeCredentials <- SonatypeCredentials.fromEnv(credentials, SonatypeCentralClient.host)
      backend = Slf4jLoggingBackend(HttpURLConnectionBackend())
    } yield new SonatypeCentralClient(backend, sonatypeCredentials)

  sealed abstract class PublishingType(private val id: String) {
    def unapply: String = id
  }

  object PublishingType {
    case object AUTOMATIC    extends PublishingType("AUTOMATIC")
    case object USER_MANAGED extends PublishingType("USER_MANAGED")
  }

  implicit val deploymentStateDecoder: Decoder[DeploymentState] = Decoder[String].emap { str =>
    DeploymentState
      .parse(str).toRight(
        s"Could not parse received value to a valid deployment state. Received value: $str. Expected value to be one of the following: ${DeploymentState.values
            .map(_.unapply).mkString(",")}"
      )
  }

  sealed abstract class DeploymentState(private val id: String) {
    def unapply: String = id
  }

  object DeploymentState {
    case object FAILED     extends DeploymentState("FAILED")
    case object PENDING    extends DeploymentState("PENDING")
    case object PUBLISHED  extends DeploymentState("PUBLISHED")
    case object PUBLISHING extends DeploymentState("PUBLISHING")
    case object VALIDATED  extends DeploymentState("VALIDATED")
    case object VALIDATING extends DeploymentState("VALIDATING")

    val values: Vector[DeploymentState] = Vector(FAILED, PENDING, PUBLISHED, PUBLISHING, VALIDATED, VALIDATING)

    def parse(str: String): Option[DeploymentState] = values.collectFirst {
      case state if state.unapply == str => state
    }
  }

  final case class DeploymentId(private val id: String) {
    def unapply: String = id
  }

  implicit val statusCheckResponseBodyDecoder: Decoder[StatusCheckResponseBody] = deriveDecoder[StatusCheckResponseBody]

  final case class StatusCheckResponseBody(
      deploymentId: String,
      deploymentName: String,
      deploymentState: DeploymentState,
      purls: Vector[String]
  )
}
