
addSbtPlugin("com.github.mpeltonen" % "sbt-idea" % "1.5.1")

addSbtPlugin("com.github.gseitz" % "sbt-release" % "0.7.1")

addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % "0.1.3")

libraryDependencies <+= sbtVersion("org.scala-sbt" % "scripted-plugin" % _)
