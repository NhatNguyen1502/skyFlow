name := "skyflow"

version := "0.1.0"

scalaVersion := "2.13.12"

lazy val akkaVersion = "2.6.21"
lazy val akkaHttpVersion = "10.2.10"

// Akka dependencies
libraryDependencies ++= Seq(
  // Akka Actor Typed
  "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream-typed" % akkaVersion,
  
  // Akka HTTP
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  
  // Akka Streams
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  
  // Akka Persistence
  "com.typesafe.akka" %% "akka-persistence-typed" % akkaVersion,
  "com.typesafe.akka" %% "akka-persistence-testkit" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-serialization-jackson" % akkaVersion,
  
  // PostgreSQL Persistence plugin
  "com.lightbend.akka" %% "akka-persistence-jdbc" % "5.0.4",
  "org.postgresql" % "postgresql" % "42.6.0",
  
  // HikariCP for connection pooling
  "com.zaxxer" % "HikariCP" % "5.0.1",
  
  // Logging
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.4.11",
  "net.logstash.logback" % "logstash-logback-encoder" % "7.4",
  
  // Testing
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % akkaVersion % Test,
  "com.typesafe.akka" %% "akka-http-testkit" % akkaHttpVersion % Test,
  "com.typesafe.akka" %% "akka-stream-testkit" % akkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.2.17" % Test
)

// Compiler options
scalacOptions ++= Seq(
  "-encoding", "UTF-8",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Xlint",
  "-Ywarn-dead-code",
  "-Ywarn-numeric-widen",
  "-Ywarn-value-discard"
)

// Parallel execution
Test / parallelExecution := false
