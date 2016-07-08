organization := "com.lightbend.lagom"

name := "lagom-service-locator-zookeeper"

version := "1.0.0-SNAPSHOT"

scalaVersion := "2.11.8"

val lagomVersion = "1.0.0-RC1"

libraryDependencies ++= Seq(
  "com.lightbend.lagom" %% "lagom-javadsl-api"   % lagomVersion,
  "org.apache.curator"   % "curator-x-discovery" % "3.1.0",
  "org.apache.curator"   % "curator-test"        % "3.1.0" % Test,
  "org.scalatest"       %% "scalatest"           % "2.2.4" % Test
)