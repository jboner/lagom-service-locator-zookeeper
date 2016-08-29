organization := "org.deeplearning4j"

name := "lagom-service-locator-zookeeper"
val lagomVersion = "1.1.0-RC1"

version := lagomVersion
javaHome := Some(file(sys.env.getOrElse("JAVA_HOME","")))

scalaVersion := "2.11.8"
scalacOptions += "-target:jvm-1.8"

javacOptions ++= Seq("-source", "1.8", "-target", "1.8", "-Xlint")
licenses += ("Apache-2.0", url("http://www.apache.org/licenses/LICENSE-2.0"))

lazy val commonSettings = Seq(
  version in ThisBuild := "<YOUR PLUGIN VERSION HERE>",
  organization in ThisBuild := "<INSERT YOUR ORG HERE>"
)

lazy val root = (project in file(".")).
  settings(
    sbtPlugin := false,
    name := "lagom-servicelocator-zookeeper",
    description := "Lagom service locator zookeeper (fork)",
    // This is an example.  bintray-sbt requires licenses to be specified
    // (using a canonical name).
    licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0.html")),
    publishMavenStyle := true,
    bintrayRepository in bintray := "lagom-servicelocator-zookeeper",
    bintrayOrganization in bintray := Some("skymindio")
  )

libraryDependencies ++= Seq(
  "com.lightbend.lagom" %% "lagom-javadsl-api"   % lagomVersion,
  "org.apache.curator"   % "curator-x-discovery" % "2.7.0",
  "org.apache.curator"   % "curator-test"        % "2.7.0" % Test,
  "org.scalatest"       %% "scalatest"           % "2.2.4" % Test
)
