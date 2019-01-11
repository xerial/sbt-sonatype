addSbtPlugin("com.github.gseitz" % "sbt-release"  % "1.0.7")
addSbtPlugin("org.xerial.sbt"    % "sbt-sonatype" % "2.3")
addSbtPlugin("com.jsuereth"      % "sbt-pgp"      % "1.1.1")
addSbtPlugin("io.get-coursier"   % "sbt-coursier" % "1.1.0-M7")
addSbtPlugin("com.geirsson"      % "sbt-scalafmt" % "1.5.1")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("releases")
