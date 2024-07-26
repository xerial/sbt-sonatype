sonatypeCredentialHost := xerial.sbt.sonatype.SonatypeCentralClient.host

val checkSonatypeDefaultResolver = taskKey[Unit]("Check if the default resolver is Sonatype")
checkSonatypeDefaultResolver := {
  val actual   = sonatypeDefaultResolver.value
  val expected = Resolver.url("https://central.sonatype.com")
  require(actual == expected, s"expected $actual to be $expected")
}
