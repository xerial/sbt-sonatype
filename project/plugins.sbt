addSbtPlugin("com.github.gseitz" % "sbt-release"  % "1.0.7")
addSbtPlugin("org.xerial.sbt"    % "sbt-sonatype" % "2.0")
addSbtPlugin("com.jsuereth"      % "sbt-pgp"      % "1.1.0")
addSbtPlugin("io.get-coursier"   % "sbt-coursier" % "1.0.0")
addSbtPlugin("com.geirsson"      % "sbt-scalafmt" % "1.3.0")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("releases")
