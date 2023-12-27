package xerial.sbt.sonatype

import wvlet.airspec.AirSpec
import xerial.sbt.sonatype.SonatypeException.MISSING_PROFILE;

class SonatypeExceptionTest extends AirSpec {
  test("give helpful advice when no profile is found") {
    val missingProfile = MISSING_PROFILE("com.gu", "oss.sonatype.org")
    missingProfile.problem shouldBe "Profile com.gu is not found on oss.sonatype.org"
    missingProfile.possibleAlternativeHosts shouldBe Seq("s01.oss.sonatype.org")
    missingProfile.advice shouldBe
      "In your sbt settings, check your sonatypeProfileName and sonatypeCredentialHost (try s01.oss.sonatype.org?)"

    MISSING_PROFILE("com.gu", "s01.oss.sonatype.org").hostAdvice shouldBe "try oss.sonatype.org?"
    MISSING_PROFILE("com.gu", "example.com").hostAdvice shouldBe "try oss.sonatype.org, or s01.oss.sonatype.org?"
  }
}
