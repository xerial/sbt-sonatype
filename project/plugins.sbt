val SONATYPE_VERSION  = sys.env.getOrElse("SONATYPE_VERSION", "3.12.2")
addSbtPlugin("org.xerial.sbt" % "sbt-sonatype"  % SONATYPE_VERSION)
addSbtPlugin("com.github.sbt" % "sbt-pgp"       % "2.3.0")
addSbtPlugin("org.scalameta"  % "sbt-scalafmt"  % "2.5.2")
addSbtPlugin("com.github.sbt" % "sbt-dynver"    % "5.1.0")
addSbtPlugin("com.eed3si9n"   % "sbt-buildinfo" % "0.13.1")

libraryDependencies += "org.scala-sbt" %% "scripted-plugin" % sbtVersion.value

resolvers += Resolver.sonatypeRepo("snapshots")
