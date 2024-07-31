package org.dodex

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.sandinh.paho.akka.ByteArrayConverters.RichByteArray
import mjson.Json

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import scala.concurrent.Promise
import scala.util.Random

object MqttClient {
  def props(
      exchange: ActorRef
  ): Props =
    Props(new MqttClient(exchange))
}

class MqttClient(
    exchange: ActorRef
) extends Actor {

  import com.sandinh.paho.akka.*
  import context.system
  import scribe.Logger
  import scala.util.control.NonFatal
  import scala.language.postfixOps

  implicit val scheduler: akka.actor.Scheduler =
    system.classicSystem.scheduler
  implicit val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global
  val handler: ActorRef = null
  val log: Logger = Logger("MqttClient")

  @volatile var failCount = 0
  @volatile var connected = false

  @inline private def urlEnc(s: String) = URLEncoder.encode(s, "utf-8")
  @inline private def urlDec(s: String) = URLDecoder.decode(s, "utf-8")

  private var isDatabaseActive: Boolean = false

  val subpub: ActorSystem = ActorSystem("actor-subpub")
  val subpaho: ActorSystem = ActorSystem("actor-subpaho")

  val receivedPubsub: Promise[Boolean] = Promise[Boolean]()
  val topic = "source-spec/manualacks"

  val pubsub: ActorRef = subpub.actorOf(
    Props[MqttPubSub](MqttPubSub(PSConfig("tcp://localhost:1883"), self)),
    "mqttsubpub"
  )

  var topicpaho: String = "paho-akka/MqttPubSubSpecIT" + Random.nextLong()

  private var subscribeToVertx: ActorRef = subpaho.actorOf(
    Props[SubscribeActor](SubscribeActor(pubsub)),
    "mqttvertx"
  )

  def receive: PartialFunction[Any, Unit] = {
    case msg: Message =>
      log.info("Message: " + RichByteArray(msg.payload).getString)
      log.info("url: " + urlEnc(msg.topic))

      exchange ! msg
    case "database" =>
      if (!isDatabaseActive) {
        exchange ! "start cassandra"
        isDatabaseActive = true
      }
      var akkaMessage: Array[Byte] = null
      try {
        akkaMessage = Json
          .`object`()
          .set("msg", "Akka Cassandra Ready")
          .set("cmd", "string")
          .toString()
          .getBytes(StandardCharsets.UTF_8)
      } catch {
        case NonFatal(e) =>
          log.error(s"problem with publish: $e")
      }

      pubsub ! Publish("akka", akkaMessage)
    case returnData @ (_: ReturnData) =>
      pubsub ! new Publish(topicpaho, returnData.json.toString.getBytes(), 0)
    case default @ _ =>
      log.warn(
        default.getClass.getSimpleName + " waiting for connection to Vertx"
      )
  }

  private class SubscribeActor(pubsub: ActorRef) extends Actor {
    pubsub ! Subscribe(topicpaho, self)

    def receive: Receive = {
      case SubscribeAck(Subscribe(topicpaho, `self`, _), fail) =>
        if (fail.isEmpty) context become ready
        else log.error("{} {}", fail.get, s"Can't subscribe to $topic")
    }

    private def ready: Receive = { case msg: Message =>
      log.debug("Received Message: " + RichByteArray(msg.payload).getString)

      pubsub ! UnSubscribe(msg.topic, self)

      topicpaho = "paho-akka/MqttDodex" + Random.nextLong()
      pubsub ! Subscribe(topicpaho, self)
      exchange ! msg
    }
  }
}
