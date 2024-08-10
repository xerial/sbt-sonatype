val SONATYPE_VERSION  = sys.env.getOrElse("SONATYPE_VERSION", "3.11.2")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype"  % SONATYPE_VERSION)
addSbtPlugin("com.github.sbt" % "sbt-pgp"       % "2.2.1")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"  % "2.5.2")
addSbtPlugin("com.github.sbt" % "sbt-dynver"    % "5.0.1")
addSbtPlugin("com.eed3si9n"   % "sbt-buildinfo" % "0.12.0")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("snapshots")
