organization := "com.lightbend.lagom"

name := "lagom-service-locator-zookeeper"

version := "1.0.0-SNAPSHOT"
javaHome := Some(file(sys.env.getOrElse("JAVA_HOME","")))

scalaVersion := "2.11.8"
scalacOptions += "-target:jvm-1.8"
val lagomVersion = "1.0.0-RC1"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")


libraryDependencies ++= Seq(
  "com.lightbend.lagom" %% "lagom-javadsl-api"   % lagomVersion,
  "org.apache.curator"   % "curator-x-discovery" % "2.11.0",
  "org.apache.curator"   % "curator-test"        % "2.11.0" % Test,
  "org.scalatest"       %% "scalatest"           % "2.2.4" % Test
)