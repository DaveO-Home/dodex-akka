package org.dodex.ex

import java.net.InetSocketAddress
import java.nio.Buffer
import java.nio.ByteBuffer

import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.Cancellable
import akka.actor.CoordinatedShutdown
import akka.actor.Props
import akka.actor.Terminated
import akka.actor.UnhandledMessage
import akka.io.IO
import akka.io.Tcp
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import akka.stream.scaladsl.Sink
import akka.util.ByteString
import mjson.Json
import org.dodex.Capsule
import org.dodex.CloseSession
import org.dodex.ReturnData
import org.dodex.SessionCassandra
import org.dodex.db.DodexCassandra
import org.dodex.db.DodexDml
import org.modellwerkstatt.javaxbus.VertXProtoMJson

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
  var parent: ActorRef = null
 
  def receive: PartialFunction[Any,Unit] = {
    case Connected(remote: InetSocketAddress, local: InetSocketAddress) =>
      log.warning("Connected to Vertx Event Bus {}", remote)
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
          if (body != null)
            if (body.at("msg") != null)
              body.at("msg")
            else 
              body
          else null

        if (message != null) {
          log.warning("Requested Command: {}", message.at("cmd").toString())
        }
        // make json object compatible with dodex-vertx
        val payload: Json =  if (body != null && body.at("msg") == null) 
          Json.`object`().set("msg", body) else body
        dodexSystem ! DodexData(self, payload)
      } catch {
        case e: Exception =>
          e.printStackTrace()
      }
    case returnData: ReturnData =>
      log.warning("Response Data Length: {}", returnData.json.toString().length())

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
    case "close" =>
      var writeBuffer =
        getBuffer(proto.unregister("akka").toString().getBytes("UTF-8"))
      sender() ! ByteString.fromByteBuffer(writeBuffer)
    case "stop dodex"   => dodexSystem ! ShutDown
    case "write failed" => log.error("Write Failed")
    case "connection closed" =>
      log.warning("Connection Closed"); self ! "stop dodex"
    case "connect failed" => log.error("Connection failed"); self ! "stop dodex"
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

  private def vertxRegister(sender: ActorRef): Unit = {
    var writeBuffer =
      getBuffer(proto.register("akka").toString().getBytes("UTF-8"))

    // Messaging (Akka service registration to Vertx Event Bus) back to Client for Tcp writing
    sender ! ByteString.fromByteBuffer(writeBuffer)

    val jsonPayLoad: Json =
      Json.`object`().set("msg", "Akka Cassandra Ready").set("cmd", "string");
    writeBuffer = getBuffer(
      proto.send("vertx", jsonPayLoad, null).toString().getBytes("UTF-8")
    )

    sender ! ByteString.fromByteBuffer(writeBuffer)
  }

  private def startupCassandra(): Unit = {
    dodexSystem ! new SessionCassandra(self, null)
  }

  private def cassandraData(sessionCassandra: SessionCassandra): Unit = {
    val version: Future[String] =
      sessionCassandra.cassandraSession
        .select("SELECT release_version FROM system.local;")
        .map(_.getString("release_version"))
        .runWith(Sink.head) // Expecting at least 1 row

    version.onComplete {
      case Success(result) => {
        log.warning(s"The Cassandra Version: $result")
      }
      case Failure(exe) =>
        val msg = exe.getMessage
        log.error("Cassandra query failed: {}", msg)
    }
  }

  private def getBuffer(asBytes: Array[Byte]): ByteBuffer = {
    val buffer: ByteBuffer = ByteBuffer.allocate(asBytes.length + 4)

    buffer.putInt(asBytes.length)
    buffer.put(asBytes)
    buffer.limit(buffer.position())
    val buffer2: Buffer = buffer.position(0) // Making compatible with java8
    buffer2.asInstanceOf[ByteBuffer]
  }
  override def unhandled(message: Any): Unit = {
    message match {
      case Terminated(dead) =>
        dead.tell("Terminated", self)
      case _ =>
        context.system.eventStream.publish(
          UnhandledMessage(message, sender(), self)
        )
    }
  }
}
