name := "chip"

version := "0.1"

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-feature",
    "-deprecation",
    "-language:implicitConversions",
    "-language:higherKinds",
    "-language:existentials",
    "-language:postfixOps",
    "-Ypartial-unification"
  )
)

wartremoverWarnings ++= Warts.unsafe

resolvers += "krasserm at bintray" at "http://dl.bintray.com/krasserm/maven"
resolvers += Resolver.sonatypeRepo("snapshots")

lazy val doobieVersion = "0.5.1"
lazy val http4sVersion = "0.18.1"
lazy val circeVersion = "0.9.1"

val app = crossProject.settings(
  unmanagedSourceDirectories in Compile +=
    baseDirectory.value  / "shared" / "main" / "scala",
  libraryDependencies ++= Seq(
    "com.lihaoyi" %%% "scalatags" % "0.6.2",
    "io.circe" %%% "circe-core" % circeVersion,
    "io.circe" %%% "circe-generic" % circeVersion,
    "io.circe" %%% "circe-literal" % circeVersion,
    "io.circe" %%% "circe-parser" % circeVersion
  ),
  scalaVersion := "2.12.4"
).jsSettings(
  skip in packageJSDependencies := false,
  libraryDependencies ++= Seq(
    "org.scala-js" %%% "scalajs-dom" % "0.9.1",
    "be.doeraene" %%% "scalajs-jquery" % "0.9.2"
  ),
  scalaJSUseMainModuleInitializer := true,
  jsDependencies += "org.webjars" % "jquery" % "2.1.4" / "2.1.4/jquery.js"
).jvmSettings(
  libraryDependencies ++= Seq(
    compilerPlugin("org.scalamacros" % "paradise" % "2.1.0" cross CrossVersion.full),
    compilerPlugin("org.spire-math" %% "kind-projector" % "0.9.4"),
    compilerPlugin("org.scalameta" % "paradise" % "3.0.0-M10" cross CrossVersion.full),

    "org.typelevel" %% "cats-core" % "1.0.1",
    "org.typelevel" %% "cats-effect" % "0.9",
    "org.tpolecat" %% "doobie-core" % doobieVersion,
    "org.tpolecat" %% "doobie-specs2" % doobieVersion,
    "org.tpolecat" %% "doobie-postgres" % doobieVersion,
    "co.fs2" %% "fs2-core" % "0.10.1",
    "org.http4s" %% "http4s-dsl" % http4sVersion,
    "org.http4s" %% "http4s-blaze-server" % http4sVersion,
    "org.http4s" %% "http4s-blaze-client" % http4sVersion,
    "org.http4s" %% "http4s-circe" % http4sVersion,
    "org.http4s" %% "http4s-circe" % circeVersion,
    "io.circe" % "circe-fs2_2.12" % "0.9.0",
    "com.chuusai" %% "shapeless" % "2.3.3",
    "com.typesafe.akka" %% "akka-http" % "10.1.0-RC2",
    "com.typesafe.akka" %% "akka-stream" % "2.5.9",
    "com.github.zainab-ali" %% "fs2-reactive-streams" % "0.5.1",
    "com.github.mpilquist" %% "simulacrum" % "0.12.0",
    "org.webjars" % "bootstrap" % "3.2.0",
    "org.reactormonk" %% "cryptobits" % "1.1",
  )
)

lazy val appJS = app.js
lazy val appJVM = app.jvm.settings(
  (resources in Compile) += (fastOptJS in (appJS, Compile)).value.data
).settings(commonSettings)