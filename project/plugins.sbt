addSbtPlugin("com.github.gseitz" % "sbt-release"  % "1.0.12")
addSbtPlugin("org.xerial.sbt"    % "sbt-sonatype" % "3.8.1")
addSbtPlugin("com.jsuereth"      % "sbt-pgp"      % "2.0.1-M3")
addSbtPlugin("org.scalameta"     % "sbt-scalafmt" % "2.2.1")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("releases")
