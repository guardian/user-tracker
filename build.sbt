import Dependencies._

organization := "com.gu"

name := "user-tracker"

scalaVersion := "2.10.4"

libraryDependencies ++= Seq(
  akkaAgent,
  akkaTestKit,
  awsSdk,
  playJson,
  scalaTest,
  slf4j
)
