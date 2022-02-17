val SONATYPE_VERSION = sys.env.getOrElse("SONATYPE_VERSION", "3.9.12-SNAPSHOT")
addSbtPlugin("com.github.sbt" % "sbt-release"  % "1.1.0")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % SONATYPE_VERSION)
addSbtPlugin("com.github.sbt" % "sbt-pgp"      % "2.1.2")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt" % "2.4.6")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("releases")
