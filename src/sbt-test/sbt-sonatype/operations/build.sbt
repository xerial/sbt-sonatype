organization           := System.getProperty("organization", "org.xerial.operations")
sonatypeProfileName    := System.getProperty("profile.name", "org.xerial")
version                := System.getProperty("version", "0.1")
sonatypeCredentialHost := System.getProperty("host", "oss.sonatype.org")
sonatypeRepository     := System.getProperty("repo", s"https${sonatypeRepository.value}//service/local")

pomExtra := (
  <url>https://github.com/xerial/sbt-sonatype</url>
  <licenses>
    <license>
      <name>Apache 2</name>
      <url>http://www.apache.org/licenses/LICENSE-2.0.txt</url>
    </license>
  </licenses>
  <scm>
    <connection>scm:git:github.com/xerial/sbt-sonatype.git</connection>
    <developerConnection>scm:git:git@github.com:xerial/sbt-sonatype.git</developerConnection>
    <url>github.com/xerial/sbt-sonatype.git</url>
  </scm>
  <developers>
    <developer>
      <id>leo</id>
      <name>Taro L. Saito</name>
      <url>http://xerial.org/leo</url>
    </developer>
  </developers>
)
