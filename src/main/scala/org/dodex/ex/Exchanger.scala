package org.dodex.ex

import akka.actor.{Actor, ActorRef, Props}
import akka.actor.ActorLogging
import akka.actor.Terminated
import akka.actor.UnhandledMessage
import akka.io.{IO, Tcp}
import java.net.InetSocketAddress
import akka.util.ByteString
import mjson.Json
import org.dodex.db.DodexCassandra
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import scala.concurrent.Future
import akka.stream.scaladsl.Sink
import scala.util.Success
import scala.util.Failure
import org.modellwerkstatt.javaxbus.VertXProtoMJson
import java.nio.Buffer
import java.nio.ByteBuffer
import org.dodex.{Capsule, SessionCassandra, CloseSession, TcpClient}
import akka.actor.Cancellable
import akka.actor.CoordinatedShutdown
import akka.Done
import org.dodex.db.DodexDml
import org.dodex.ReturnData

case class DodexData(
    sender: akka.actor.ActorRef,
    json: mjson.Json
) extends Capsule

case object ShutDown extends Capsule

class Exchanger extends Actor with ActorLogging {
  import Tcp._
  import context.system
  import akka.actor.DeathPactException
  import scala.language.postfixOps

  private val proto: VertXProtoMJson = new VertXProtoMJson()
  implicit val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global
  private val dodexSystem =
    akka.actor.typed.ActorSystem[Capsule](DodexCassandra(), "dodex-system")
  var parent: ActorRef = null;
  @volatile var can: Cancellable =
    CoordinatedShutdown(system).addCancellableTask(
      CoordinatedShutdown.PhaseBeforeServiceUnbind,
      "cleanup"
    ) { () =>
      Future {
        log.info("Cassandra Terminating Exchanger")
        system.terminate()
        Done
      }
    }
  def receive = {
    case Connected(remote: InetSocketAddress, local: InetSocketAddress) =>
      log.error("Connected to Vertx Event Bus {}", remote)
      ping(sender())
      vertxRegister(sender())
      parent = sender()

    case sessionCassandra @ (_: SessionCassandra) =>
      cassandraData(sessionCassandra)

    case data: ByteString =>
      if (data.slice(0, 20).utf8String.contains("pong")) {
        startupCassandra()
      }
      // Vertx Event Bus uses first 4 bytes
      try {
        val length = data.length - 4
        val jsonString = data.takeRight(length).utf8String
        val vertxJson: Json = Json.read(jsonString)
        val body: Json = vertxJson.at("body")
        val message: mjson.Json =
          if (body != null && body.at("msg") != null)
            body.at("msg")
          else null
        if (message != null) {
          println(s"The Message: $message")
        }
        dodexSystem ! new DodexData(self, body)
      } catch {
        case e: Exception =>
          e.printStackTrace()
      }
    case returnData: ReturnData =>
      var stuff: Json = null
      println("Proto: " + proto.send("vertx", returnData.json, null).toString())

      try {
        val writeBuffer = getBuffer(
          proto
            .send("vertx", returnData.json, null)
            .toString()
            .getBytes("UTF-8")
        )
        parent ! ByteString.fromByteBuffer(writeBuffer)
      } catch {
        case e: Exception =>
          e.printStackTrace()
      }
    case "stop dodex"        => dodexSystem ! ShutDown
    case "write failed"      => log.error("Write Failed")
    case "connection closed" => log.info("Connection Closed"); self ! "stop dodex"
    case "connect failed"    => log.error("Connection failed"); self ! "stop dodex"      
    case default @ (_: Any) =>
      log.warning(
        "Default: {} : {} -- {}" + default.getClass.getSimpleName,
        default,
        "waiting for Vertx to start"
      )
  }

  def ping(sender: ActorRef): Unit = {
    var writeBuffer = getBuffer(proto.ping().toString().getBytes("UTF-8"))
    sender ! ByteString.fromByteBuffer(writeBuffer)
  }

  def vertxRegister(sender: ActorRef): Unit = {
    var writeBuffer =
      getBuffer(proto.register("akka").toString().getBytes("UTF-8"))

    // Messaging (Akka service registration to Vertx Event Bus) back to Client for Tcp writing
    sender ! ByteString.fromByteBuffer(writeBuffer)

    var jsonPayLoad: Json =
      Json.`object`().set("msg", "Akka Cassandra Ready").set("cmd", "string");
    writeBuffer = getBuffer(
      proto.send("vertx", jsonPayLoad, null).toString().getBytes("UTF-8")
    )

    sender ! ByteString.fromByteBuffer(writeBuffer)
  }

  def startupCassandra(): Unit = {
    dodexSystem ! new SessionCassandra(self, null)
  }

  def cassandraData(sessionCassandra: SessionCassandra): Unit = {
    val version: Future[String] =
      sessionCassandra.cassandraSession
        .select("SELECT release_version FROM system.local;")
        .map(_.getString("release_version"))
        .runWith(Sink.head) // Expecting at least 1 row

    version.onComplete {
      case Success(result) => {
        log.warning(s"The Cassandra Version: $result")
      }
      case Failure(exe) => {
        val msg = exe.getMessage()
        log.error("Cassandra query failed: {}", msg)
      }
    }
  }

  def getBuffer(asBytes: Array[Byte]): ByteBuffer = {
    val buffer: ByteBuffer = ByteBuffer.allocate(asBytes.length + 4)

    buffer.putInt(asBytes.length)
    buffer.put(asBytes)
    buffer.limit(buffer.position())
    val buffer2: Buffer = buffer.position(0)  // Making compatiable with java8
    buffer2.asInstanceOf[ByteBuffer]
  }

  override def unhandled(message: Any): Unit = {
    message match {
      case Terminated(dead) => throw DeathPactException(dead)
      case _ =>
        context.system.eventStream.publish(
          UnhandledMessage(message, sender(), self)
        )
    }
  }
}
