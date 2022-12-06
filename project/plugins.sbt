val SONATYPE_VERSION = sys.env.getOrElse("SONATYPE_VERSION", "3.9.15")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype" % SONATYPE_VERSION)
addSbtPlugin("com.github.sbt" % "sbt-pgp"      % "2.2.1")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt" % "2.5.0")
addSbtPlugin("com.dwijnand"   % "sbt-dynver"   % "4.1.1")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("snapshots")
