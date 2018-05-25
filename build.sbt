name := "gossipApps"

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

lazy val utils = (project in file("utils")).settings(commonSettings)
lazy val backend = (project in file("backend")).settings(commonSettings).dependsOn(utils, streamz)
lazy val chip = (project in file("chip")).settings(commonSettings).dependsOn(backend, utils)
lazy val vault = (project in file("vault")).settings(commonSettings).dependsOn(backend, utils)
lazy val gossipServer = (project in file("gossipServer")).settings(commonSettings).dependsOn(backend)

lazy val streamz = ProjectRef(uri("git://github.com/notxcain/streamz.git#abstract-effect"), "converter")
