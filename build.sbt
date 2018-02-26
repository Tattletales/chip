name := "chip"

version := "0.1"

scalaVersion := "2.12.4"

scalacOptions += "-Ypartial-unification"

wartremoverWarnings ++= Warts.unsafe

libraryDependencies += "org.typelevel" %% "cats-core" % "1.0.1"
libraryDependencies ++= Seq("com.chuusai" %% "shapeless" % "2.3.3")

libraryDependencies += "io.frees" %% "frees-core" % "0.7.0"
libraryDependencies += "io.frees" %% "frees-cache" % "0.7.0"


addCompilerPlugin("org.spire-math" %% "kind-projector" % "0.9.4")
addCompilerPlugin("org.scalameta" % "paradise" % "3.0.0-M10" cross CrossVersion.full)

