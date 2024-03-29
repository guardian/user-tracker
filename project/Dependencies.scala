import sbt._

object Dependencies {
  val akkaAgent = "com.typesafe.akka" %% "akka-agent" % "2.3.3"
  val akkaTestKit = "com.typesafe.akka" %% "akka-testkit" % "2.3.3"
  val awsSdk = "com.amazonaws" % "aws-java-sdk" % "1.7.9"
  val playJson = "com.typesafe.play" %% "play-json" % "2.3.0"
  val scalaTest = "org.scalatest" % "scalatest_2.10" % "2.2.0" % "test"
  val slf4j = "org.clapper" %% "grizzled-slf4j" % "1.0.1"
}
