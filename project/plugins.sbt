
addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.5.1")

addSbtPlugin("com.github.gseitz" % "sbt-release" % "0.8.2")

addSbtPlugin("com.mojolly.scalate" % "xsbt-scalate-generator" % "0.4.2")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "0.2.0")

addSbtPlugin("com.typesafe.sbt" % "sbt-pgp" % "0.8.1")

libraryDependencies <+= sbtVersion("org.scala-sbt" % "scripted-plugin" % _)
