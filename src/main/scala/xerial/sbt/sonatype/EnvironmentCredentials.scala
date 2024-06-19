package xerial.sbt.sonatype

final class UsernameAndPassword(val username: String, val password: String)

object UsernameAndPassword {
  def fromToken(token: String): UsernameAndPassword = token.split(':') match {
    case Array(user, pass) => new UsernameAndPassword(user, pass)
  }
}

object EnvironmentCredentials {
  val Username = "SONATYPE_USERNAME"
  val Password = "SONATYPE_PASSWORD"
  val Token    = "SONATYPE_TOKEN"

  def getUsernameAndPassword(envVars: Map[String, String] = sys.env): Option[UsernameAndPassword] =
    envVars.get(Token).map(UsernameAndPassword.fromToken).orElse {
      for {
        u <- envVars.get(Username)
        p <- envVars.get(Password)
      } yield new UsernameAndPassword(u, p)
    }
}
