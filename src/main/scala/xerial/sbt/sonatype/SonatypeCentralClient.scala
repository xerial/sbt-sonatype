package xerial.sbt.sonatype

import com.lumidion.sonatype.central.client.core.{
  DeploymentId,
  DeploymentName,
  DeploymentState,
  PublishingType,
  SonatypeCentralError
}
import com.lumidion.sonatype.central.client.core.SonatypeCentralError.AuthorizationError
import com.lumidion.sonatype.central.client.gigahorse.SyncSonatypeClient
import gigahorse.support.okhttp.Gigahorse
import gigahorse.HttpClient
import java.io.File
import sbt.librarymanagement.ivy.Credentials
import scala.concurrent.ExecutionContext
import scala.math.pow
import scala.util.Try
import wvlet.log.LogSupport
import xerial.sbt.sonatype.utils.Extensions.*
import xerial.sbt.sonatype.SonatypeException.{BUNDLE_UPLOAD_FAILURE, STATUS_CHECK_FAILURE, USER_ERROR}

private[sonatype] class SonatypeCentralClient(
    client: SyncSonatypeClient,
    httpClient: HttpClient
) extends AutoCloseable
    with LogSupport {

  private def retryRequest[A](
      request: => Either[SonatypeCentralError, A],
      errorContext: String,
      errorCode: ErrorCode,
      retriesLeft: Int,
      retriesAttempted: Int = 0
  ): Either[SonatypeException, A] = {
    for {
      response <- Try(request).toEither.leftMap { err =>
        SonatypeException(errorCode, s"$errorContext. ${err.getMessage}")
      }
      finalResponse <- response match {
        case Left(AuthorizationError(msg)) =>
          Left(
            new SonatypeException(USER_ERROR, s"Authorization error. Message Received: $msg")
          )
        case Left(ex) =>
          if (retriesLeft > 0) {
            val exponent                   = pow(5, retriesAttempted).toInt
            val maximum                    = 30000
            val initialMillisecondsToSleep = 1500 + exponent
            val finalMillisecondsToSleep = if (maximum < initialMillisecondsToSleep) {
              maximum
            } else initialMillisecondsToSleep
            Thread.sleep(finalMillisecondsToSleep)
            error(s"$errorContext. Request failed with the following message: ${ex.getMessage}. Retrying request.")
            retryRequest(request, errorContext, errorCode, retriesLeft - 1, retriesAttempted + 1)
          } else {
            Left(SonatypeException(errorCode, ex.getMessage))
          }
        case Right(res) => Right(res)
      }
    } yield finalResponse
  }
  def uploadBundle(
      localBundlePath: File,
      deploymentName: DeploymentName,
      publishingType: Option[PublishingType]
  ): Either[SonatypeException, DeploymentId] = {
    info(s"Uploading bundle ${localBundlePath.getPath} to Sonatype Central")

    retryRequest(
      client.uploadBundle(localBundlePath, deploymentName, publishingType),
      "Error uploading bundle to Sonatype Central",
      BUNDLE_UPLOAD_FAILURE,
      60
    )
  }

  def didDeploySucceed(
      deploymentId: DeploymentId,
      previousDeployState: Option[DeploymentState] = None
  ): Either[SonatypeException, Boolean] = {

    for {
      responseOpt <- retryRequest(
        client.checkStatus(deploymentId),
        "Error checking deployment status",
        STATUS_CHECK_FAILURE,
        10
      )
      response <- responseOpt.toRight(
        SonatypeException(
          STATUS_CHECK_FAILURE,
          s"Failed to check status for deployment id: ${deploymentId.unapply}. Deployment not found."
        )
      )
      finalRes <-
        if (response.deploymentState.isNonFinal) {
          Thread.sleep(5000L)
          didDeploySucceed(deploymentId)
        } else if (response.deploymentState == DeploymentState.FAILED) {
          error(
            s"Deployment failed for deployment id: ${deploymentId.unapply}. Current deployment state: ${response.deploymentState.unapply}"
          )
          Right(false)
        } else if (
          response.deploymentState == DeploymentState.PENDING || response.deploymentState == DeploymentState.VALIDATING
        ) {
          val shouldStateBeLogged = previousDeployState.forall { previousState =>
            if (previousState != response.deploymentState) {
              true
            } else {
              false
            }
          }
          if (shouldStateBeLogged) {
            info(s"Current deployment state: ${response.deploymentState.unapply}")
          }
          Thread.sleep(5000L)
          didDeploySucceed(deploymentId)
        } else {
          info(
            s"Deployment succeeded for deployment id: ${deploymentId.unapply}. Current deployment state: ${response.deploymentState.unapply}"
          )
          Right(true)
        }
    } yield finalRes
  }

  override def close(): Unit = httpClient.close()
}

object SonatypeCentralClient {
  val host: String = "central.sonatype.com"

  def fromCredentials(
      credentials: Seq[Credentials]
  )(implicit ec: ExecutionContext): Either[SonatypeException, SonatypeCentralClient] =
    for {
      sonatypeCredentials <- SonatypeCredentials.fromEnv(credentials, host)
      httpClient = Gigahorse.http(Gigahorse.config)
      client = new SyncSonatypeClient(
        sonatypeCredentials.toSonatypeCentralCredentials,
        httpClient,
        60
      )
    } yield new SonatypeCentralClient(client, httpClient)
}
