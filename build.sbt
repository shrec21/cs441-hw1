ThisBuild / scalaVersion := "3.5.1"

name := "cs441-hw1"

libraryDependencies ++= Seq(
  "org.scalatest" %% "scalatest" % "3.2.19" % Test,

  "com.typesafe" % "config" % "1.4.3",

  "org.slf4j" % "slf4j-api" % "2.0.13",
  "ch.qos.logback" % "logback-classic" % "1.5.6",

  "org.apache.pdfbox" % "pdfbox" % "2.0.31",

  "com.softwaremill.sttp.client3" %% "core"  % "3.9.5",
  "com.softwaremill.sttp.client3" %% "circe" % "3.9.5",
  "io.circe" %% "circe-generic" % "0.14.9",
  "io.circe" %% "circe-parser"  % "0.14.9",

  "org.apache.lucene" % "lucene-core" % "9.10.0",
  "org.apache.lucene" % "lucene-analysis-common" % "9.10.0",
)

