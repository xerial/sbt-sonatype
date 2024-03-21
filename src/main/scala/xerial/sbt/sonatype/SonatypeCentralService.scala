package xerial.sbt.sonatype

import com.lumidion.sonatype.central.client.core.{DeploymentName, PublishingType}
import java.io.{File, FileInputStream, FileOutputStream}
import java.nio.file.{Files, Path}
import java.util.zip.{ZipEntry, ZipOutputStream}
import scala.util.Try
import wvlet.log.LogSupport
import xerial.sbt.sonatype.utils.Extensions.*
import xerial.sbt.sonatype.SonatypeException.{BUNDLE_ZIP_ERROR, STAGE_FAILURE}

class SonatypeCentralService(client: SonatypeCentralClient) extends LogSupport {

  def uploadBundle(
      localBundlePath: File,
      deploymentName: DeploymentName,
      publishingType: PublishingType
  ): Either[SonatypeException, Unit] = for {
    bundleZipDirectory <- Try(Files.createDirectory(Path.of(s"${localBundlePath.getPath}-bundle"))).toEither.leftMap {
      err =>
        SonatypeException(BUNDLE_ZIP_ERROR, s"Error creating bundle zip directory. ${err.getMessage}")
    }
    zipFile <- Try(zipDirectory(localBundlePath, bundleZipDirectory)).toEither.leftMap { err =>
      SonatypeException(BUNDLE_ZIP_ERROR, err.getMessage)
    }
    deploymentId <- client.uploadBundle(zipFile, deploymentName, Some(publishingType))
    _ = info(s"Checking if deployment succeeded for deployment id: ${deploymentId.unapply}...")
    didDeploySucceed <- client.didDeploySucceed(deploymentId, publishingType == PublishingType.AUTOMATIC)
    _ <- Either.cond(
      didDeploySucceed,
      (),
      SonatypeException(
        STAGE_FAILURE,
        s"Deployment failed. Deployment id: ${deploymentId.unapply}. Deployment name: ${deploymentName.unapply}"
      )
    )
  } yield ()

  private def zipDirectory(localBundlePath: File, bundleZipDirPath: Path): File = {
    val outputZipFilePath = s"${bundleZipDirPath.toFile.getPath}/bundle.zip"
    val fileOutputStream  = new FileOutputStream(outputZipFilePath)
    val zipOutputStream   = new ZipOutputStream(fileOutputStream)
    zipFile(localBundlePath, localBundlePath.getName, zipOutputStream, isDirTopLevel = true)
    zipOutputStream.close()
    fileOutputStream.close()

    new File(outputZipFilePath)
  }

  private def zipFile(fileToZip: File, fileName: String, zipOut: ZipOutputStream, isDirTopLevel: Boolean): Unit = {
    if (fileToZip.isHidden) return
    if (fileToZip.isDirectory) {
      if (!isDirTopLevel) {
        if (fileName.endsWith("/")) {
          zipOut.putNextEntry(new ZipEntry(fileName))
          zipOut.closeEntry()
        } else {
          zipOut.putNextEntry(new ZipEntry(fileName + "/"))
          zipOut.closeEntry()
        }
      }
      val children = fileToZip.listFiles
      val directoryPath = if (isDirTopLevel) {
        ""
      } else fileName + "/"
      for (childFile <- children) {
        zipFile(childFile, directoryPath + childFile.getName, zipOut, isDirTopLevel = false)
      }
      return
    }
    val fileInputStream = new FileInputStream(fileToZip)
    val zipEntry        = new ZipEntry(fileName)
    zipOut.putNextEntry(zipEntry)
    val bytes  = new Array[Byte](1024)
    var length = 0
    while ({ length = fileInputStream.read(bytes); length } >= 0) {
      zipOut.write(bytes, 0, length)
    }
    fileInputStream.close()
  }
}
