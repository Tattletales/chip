name := "utils"

version := "0.1"

scalaVersion := "2.12.4"

wartremoverWarnings ++= Warts.unsafe

resolvers += "krasserm at bintray".at("http://dl.bintray.com/krasserm/maven")
resolvers += Resolver.sonatypeRepo("snapshots")

libraryDependencies ++= Seq(
  "org.typelevel" %% "cats-core" % "1.1.0",
  "org.typelevel" %% "cats-effect" % "1.0.0-RC",
  "co.fs2" %% "fs2-core" % "0.10.4",
  "co.fs2" %% "fs2-io" % "0.10.4",
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "com.typesafe.scala-logging" %% "scala-logging" % "3.9.0",
)
