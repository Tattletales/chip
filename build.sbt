name := "chip"

version := "0.1"

scalaVersion := "2.12.4"

scalacOptions += "-Ypartial-unification"

wartremoverWarnings ++= Warts.unsafe

resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"
resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies += "org.typelevel" %% "cats-core" % "1.0.1"
libraryDependencies += "org.typelevel" %% "cats-effect" % "0.9"

lazy val doobieVersion = "0.5.1"
libraryDependencies ++= Seq(
  "org.tpolecat" %% "doobie-core" % doobieVersion,
  "org.tpolecat" %% "doobie-specs2" % doobieVersion,
  "org.tpolecat" %% "doobie-postgres" % doobieVersion
)

libraryDependencies += "co.fs2" %% "fs2-core" % "0.10.1"

lazy val http4sVersion = "0.18.1"
libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion
)

//libraryDependencies += "io.monix" %% "monix" % "2.3.3"
//libraryDependencies += "io.monix" %% "monix-types" % "2.3.3"
//libraryDependencies += "io.monix" %% "monix-cats" % "2.3.3"

val circeVersion = "0.9.1"

libraryDependencies ++= Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-literal",
  "io.circe" %% "circe-parser",
  "org.http4s" %% "http4s-circe"
).map(_ % circeVersion)

libraryDependencies += "io.circe" % "circe-fs2_2.12" % "0.9.0"

//libraryDependencies += "org.log4s" %% "log4s" % "1.5.0"

libraryDependencies ++= Seq("com.chuusai" %% "shapeless" % "2.3.3")

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-http" % "10.1.0-RC2",
  "com.typesafe.akka" %% "akka-stream" % "2.5.9"
)

libraryDependencies += "com.github.zainab-ali" %% "fs2-reactive-streams" % "0.5.1"

addCompilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full)

libraryDependencies += "com.github.mpilquist" %% "simulacrum" % "0.12.0"

addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.4")
addCompilerPlugin("org.scalameta" % "paradise" % "3.0.0-M10" cross CrossVersion.full)
