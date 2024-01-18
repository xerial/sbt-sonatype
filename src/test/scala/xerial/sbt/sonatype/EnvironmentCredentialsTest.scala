package xerial.sbt.sonatype

import wvlet.airspec.AirSpec

class EnvironmentCredentialsTest extends AirSpec {
  test("Extract a username & password from a SONATYPE_TOKEN environment variable") {
    val usernameAndPassword = EnvironmentCredentials
      .getUsernameAndPassword(
        Map(
          "SONATYPE_TOKEN" -> "user:pass"
        )
      ).get

    usernameAndPassword.username shouldBe "user"
    usernameAndPassword.password shouldBe "pass"
  }

  test("Extract a legacy Sonatype Username & Password from environment variables") {
    val usernameAndPassword = EnvironmentCredentials
      .getUsernameAndPassword(
        Map(
          "SONATYPE_USERNAME" -> "user",
          "SONATYPE_PASSWORD" -> "pass"
        )
      ).get

    usernameAndPassword.username shouldBe "user"
    usernameAndPassword.password shouldBe "pass"
  }

  test("Prefer a Sonatype token over a legacy Sonatype Username & Password") {
    val usernameAndPassword = EnvironmentCredentials
      .getUsernameAndPassword(
        Map(
          "SONATYPE_TOKEN"    -> "newUser:newPass",
          "SONATYPE_USERNAME" -> "oldUser",
          "SONATYPE_PASSWORD" -> "oldPass"
        )
      ).get

    usernameAndPassword.username shouldBe "newUser"
    usernameAndPassword.password shouldBe "newPass"
  }
}
