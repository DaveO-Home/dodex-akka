name := "akka-dodex-scala"

resolvers += "Akka library repository".at("https://repo.akka.io/maven")

version := "2.1.0"

scalaVersion := "3.4.2"

val AkkaVersion = "2.9.4"
val AkkaPersistenceCassandraVersion = "1.1.1"
val AkkaHttpVersion = "10.5.3"
val AkkaProjectionVersion = "1.1.0"
lazy val akkaVersion = "2.9.4"

dependencyOverrides ++= Seq()
libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-cluster-sharding-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-protobuf-v3" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor" % AkkaVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % AkkaHttpVersion,
  "com.typesafe.akka" %% "akka-persistence-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-persistence-query" % AkkaVersion,
  "com.typesafe.akka" %% "akka-discovery" % AkkaVersion,
  "com.typesafe.akka" %% "akka-persistence-cassandra-launcher" % AkkaPersistenceCassandraVersion,
  "com.typesafe.akka" %% "akka-cluster-tools" % AkkaVersion,
  "com.typesafe.akka" %% "akka-actor-testkit-typed" % AkkaVersion % Test,
  "com.lightbend.akka" %% "akka-projection-cassandra" % "1.5.0-M4",
  "com.lightbend.akka" %% "akka-stream-alpakka-mqtt" % "8.0.0",
  "org.eclipse.paho" % "org.eclipse.paho.client.mqttv3" % "1.2.5",

  "com.outr" %% "scribe" % "3.15.0",
  "com.outr" %% "scribe-file" % "3.14.0",

  "ch.qos.logback" % "logback-classic" % "1.5.6",
  "org.sharegov" % "mjson" % "1.4.1",
  "com.datastax.oss" % "java-driver-query-builder" % "4.17.0",
  "org.scalatest" %% "scalatest" % "3.3.0-SNAP4" % Test,
//  "com.typesafe.akka" % "akka-stream_3" % "2.9.3"

//  https://repo.akka.io/maven/com/lightbend/akka/akka-stream-alpakka-mqtt_3/8.0.0/akka-stream-alpakka-mqtt_3-8.0.0.pom
//  "com.typesafe.akka" %% "akka-stream-testkit" % AkkaVersion % Test

// Libraries for the Github Embedded Cassandra
//  "com.github.nosan" % "embedded-cassandra" % "4.0.7"
//  "org.apache.commons" % "commons-compress" % "1.21" % Compile,
//  "org.yaml" % "snakeyaml" % "1.30" % Compile,
//  "org.slf4j" % "slf4j-api" % "1.7.36" % Compile
)

unmanagedSources / excludeFilter := "Json.java"

def sysPropOrDefault(propName:String,default:String):String = Option(System.getProperty("dev")).getOrElse("true")

initialize ~= { _ =>
  System.setProperty("dev", sysPropOrDefault("dev","false"))  // this overrides value in application.conf
}

scalacOptions := Seq("-unchecked", "-deprecation")
enablePlugins(JavaAppPackaging, GraalVMNativeImagePlugin)

Compile / run / mainClass := Some("org.dodex.TcpClientMain") 
Compile / packageBin / mainClass := Some("org.dodex.TcpClientMain")

assembly / assemblyMergeStrategy := {
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case x => MergeStrategy.first 
}
// If using with "scala" e.g.  "scala akka-dodex-scala-assembly-1.0.jar"
assembly / assemblyOption := (assembly / assemblyOption).value.copy(includeScala = false)
assembly / assemblyMergeStrategy := {   
  case PathList("META-INF", xs @ _*) => MergeStrategy.discard
  case "reference.conf" => MergeStrategy.concat
  // case x => MergeStrategy.first
  case x =>
    val oldStrategy = (assembly / assemblyMergeStrategy).value
    oldStrategy(x)
}


assembly / mainClass := Some("org.dodex.TcpClientMain")
assembly / logLevel := Level.Error
assembly / test := {}

maintainer := "daveo@dodex.org"

// Run these commands in the Sbt shell when developing with Cassandra
// Ctrl-C will kill the fork and the embedded Cassandra - a rerun will run new code
// set fork in run := true
// set run / javaOptions += "-Ddev=true"
// run
