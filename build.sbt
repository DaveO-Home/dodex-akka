name := "akka-dodex-scala"

version := "1.0"

scalaVersion := "2.13.3"
val AkkaVersion = "2.6.10"
val AkkaPersistenceCassandraVersion = "1.0.3"
val AkkaHttpVersion = "10.1.12"
val AkkaProjectionVersion = "0.3"
lazy val akkaVersion = "2.6.10"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-protobuf-v3" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
  "com.typesafe.akka" %% "akka-serialization-jackson" % AkkaVersion,
  "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
  "com.typesafe.akka" %% "akka-persistence-cassandra" % AkkaPersistenceCassandraVersion,
  "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion,
  "com.lightbend.akka" %% "akka-projection-eventsourced" % AkkaProjectionVersion,
  "com.lightbend.akka" %% "akka-projection-cassandra" % AkkaProjectionVersion,
  "com.lightbend.akka" %% "akka-stream-alpakka-cassandra" % "2.0.2",
  "org.scala-lang" % "scala-library" % "2.13.3",
  "com.typesafe.akka" %% "akka-cluster-tools" % AkkaVersion,
  "ch.qos.logback" % "logback-classic" % "1.2.3",
  "org.sharegov" % "mjson" % "1.4.1",
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % "2.6.10" % Test,
  // "org.scalatest" %% "scalatest" % "3.3.0-SNAP2" % Test
  // "org.scalatest" %% "scalatest" % "3.2.2" % Test
  "com.datastax.oss" % "java-driver-query-builder" % "4.3.1",
  "org.scalatest" %% "scalatest" % "3.1.4" % Test
)

unmanagedSources / excludeFilter := "Json.java"

def sysPropOrDefault(propName:String,default:String):String = Option(System.getProperty("dev")).getOrElse("true")

initialize ~= { _ =>
  System.setProperty("dev", sysPropOrDefault("dev","false"))  // this overrides value in application.conf
}

scalacOptions := Seq("-unchecked", "-deprecation")
enablePlugins(JavaAppPackaging, GraalVMNativeImagePlugin)

mainClass in (Compile, run) := Some("org.dodex.TcpClient")
mainClass in (Compile, packageBin) := Some("org.dodex.TcpClient")

// If using with "scala" e.g.  "scala akka-dodex-scala-assembly-1.0.jar"
// assemblyOption in assembly := (assemblyOption in assembly).value.copy(includeScala = false)
assemblyMergeStrategy in assembly := {   
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard   
  case x => MergeStrategy.first
  case "reference.conf" => MergeStrategy.concat
}
mainClass in assembly := Some("org.dodex.TcpClient")
logLevel in assembly := Level.Error
test in assembly := {}

maintainer := "daveo@dodex.org"

// Run these commands in the Sbt shell when developing with Cassandra
// Ctrl-C will kill the fork and the embedded Cassandra - a rerun will run new code
// set fork in run := true
// set run / javaOptions += "-Ddev=true"
// run