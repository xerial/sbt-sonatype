package xerial.sbt

package object sonatype {
  final case class DeploymentName private (private val name: String) {
    def unapply: String = name
  }

  object DeploymentName {
    def fromRawString(str: String): DeploymentName = DeploymentName(str)

    def fromArtifact(organizationName: String, artifactName: String, version: String): DeploymentName = DeploymentName(
      s"${organizationName}.$artifactName-$version"
    )
  }
}
