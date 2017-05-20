// sbt-release doesn't support sbt-1.0.0 yet
addSbtPlugin("com.github.gseitz" % "sbt-release" % "1.0.5")
addSbtPlugin("com.jsuereth" % "sbt-pgp" % "1.1.0-M1")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "2.0.0-M1")

libraryDependencies += "org.scala-sbt" % "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("releases")
