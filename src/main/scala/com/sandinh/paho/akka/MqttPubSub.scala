package com.sandinh.paho.akka

import akka.actor.{FSM, Props, Terminated}
import com.sandinh.paho.akka.MqttPubSub.*
import org.dodex.TcpClient
import org.eclipse.paho.client.mqttv3.*
import org.eclipse.paho.client.mqttv3.MqttException.{REASON_CODE_CLIENT_NOT_CONNECTED, REASON_CODE_MAX_INFLIGHT}
import org.eclipse.paho.client.mqttv3.internal.MessageCatalog

import java.net.{URLDecoder, URLEncoder}
import scala.collection.mutable
import scala.util.control.NonFatal
//import org.slf4j.LoggerFactory

import scribe.Logger

import scala.concurrent.duration.*
import scala.concurrent.{Await, Future, Promise}
import scala.util.Try
object MqttPubSub {
//  private[akka] val logger = LoggerFactory.getLogger("MqttPubSub")
  val logger: Logger = Logger("MqttPubSub")
  // ++++ ultilities ++++//
  @inline private def urlEnc(s: String) = URLEncoder.encode(s, "utf-8")
  @inline private def urlDec(s: String) = URLDecoder.decode(s, "utf-8")
}

/** Notes:
  *   1. MqttClientPersistence will be set to null. @see
  *      org.eclipse.paho.client.mqttv3.MqttMessage#setQos(int) 2. MQTT client
  *      will auto-reconnect
  */
class MqttPubSub(cfg: PSConfig, owner: akka.actor.ActorRef)
    extends FSM[PSState, Unit] {
  // setup MqttAsyncClient without MqttClientPersistence
  private val client: MqttAsyncClient = {
    val c = new MqttAsyncClient(cfg.brokerUrl, cfg.clientId(), null)
    c.setCallback(new PubSubMqttCallback(self))
    c
  }
  // setup Connnection IMqttActionListener
  private val conListener: ConnListener = new ConnListener(self)

  private val subsListener = new SubscribeListener(self)

  /** Use to stash the Publish messages when disconnected. `Long` in Elem type
    * is System.nanoTime when receive the Publish message. We need republish to
    * the underlying mqtt client when go to connected.
    */
  private val pubStash = mutable.Queue.empty[(Long, Publish)]

  /** Use to stash the Subscribe messages when disconnected. We need resubscribe
    * all subscriptions to the underlying mqtt client when go to connected , so
    * we store in a separated stash (instead of merging pubStash & subStash).
    */
  private val subStash = mutable.Queue.empty[Subscribe]

  /** contains all successful `Subscribe`. + add when the underlying client
    * notify that the corresponding topic is successful subscribed. + remove
    * when the Subscribe.ref actor is terminated. + use to re-subscribe after
    * reconnected.
    */
  private val subscribed = mutable.Set.empty[Subscribe]

  /** contains all subscribing `Subscribe`. + always add when calling the
    * underlying client.subscribe. + always remove when the call is failed
    * and/or remove later when the underlying client notify that the
    * subscription has either Success or Failed.
    */
  private val subscribing = mutable.Set.empty[Subscribe]

  // reconnect attempt count, reset when connect success
  private var connectCount = 0

//  var isConnected: Boolean = false
  // ++++ FSM logic ++++
  startWith(DisconnectedState, ())

  when(DisconnectedState) {
    case Event(Connect, _) =>
      if (client.isConnected) {
        self ! Connected
      } else {
        logger.debug(s"connecting to ${cfg.brokerUrl}..")
        // paho client 1.2.0+ will reconnect automaticly (if we call MqttConnectOptions.setReconnect(true))
        // If the client application calls connect after it had reconnected, an invalid state error will be thrown.
        // See https://github.com/eclipse/paho.mqtt.java/issues/9
        try {
          client.connect(cfg.conOpt.get, null, conListener)
          logger.debug("connected?: " + client.isConnected)
          owner ! client
        } catch {
          case NonFatal(e) =>
            logger.error(s"can't connect to $cfg", e)
            delayConnect()
        }
        connectCount += 1
      }
      stay()

    case Event(Connected, _) =>
      connectCount = 0
      subscribed foreach doSubscribe
      while (subStash.nonEmpty) self ! subStash.dequeue()
      // remove expired Publish messages
      if (cfg.stashTimeToLive.isFinite)
        pubStash.dequeueAll(
          _._1 + cfg.stashTimeToLive.toNanos < System.nanoTime
        )
      while (pubStash.nonEmpty) self ! pubStash.dequeue()._2
      try {
        owner ! "database"
      } catch {
        case NonFatal(e) =>
          logger.error(s"database problem ${owner.path.name}", e)
      }
      goto(ConnectedState)

    case Event(x: Publish, _) =>
      if (pubStash.length > cfg.stashCapacity)
        while (pubStash.length > cfg.stashCapacity / 2)
          pubStash.dequeue()
      pubStash += (System.nanoTime -> x)
      stay()

    case Event(x: Subscribe, _) =>
      subStash += x
      stay()

    case Event(PublishComplete(qos0), _) =>
      if (qos0) inflight0 -= 1
      else inflight -= 1
      stay()

    case default @ _ =>
      logger.info(
        "Default: " + default.getClass.getSimpleName + default.toString + " waiting for Vertx to start"
      )
      delayConnect()
      connectCount += 1
      stay()
  }

  private var inflight0 = 0
  private var inflight = 0

  when(ConnectedState) {
    case Event(p: Publish, _)
        if inflight0 + inflight >= cfg.conOpt.maxInflight ||
          p.qos > 0 && inflight >= cfg.conOpt.maxInflightQos12 =>
      pubStash += (System.nanoTime -> p)
      stay()

    case Event(p: Publish, _) =>
      val onPublish = new PublishListener(self, p.qos == 0)
      try {
        client.publish(p.topic, p.message(), null, onPublish)
        if (p.qos == 0) inflight0 += 1
        else inflight += 1
      } catch {
        // underlying client can be disconnected when this FSM is in state SConnected. See ResubscribeSpec
        case e: MqttException
            if e.getReasonCode == REASON_CODE_CLIENT_NOT_CONNECTED =>
          self ! Disconnected // delayConnect & goto SDisconnected
          self ! p // stash p
        case e: MqttException if e.getReasonCode == REASON_CODE_MAX_INFLIGHT =>
          // This case should not be matched because we limit inflight message using inflight & inflight0
          logger.error(
            s"can't publish to ${p.topic}. ${MessageCatalog
                .getMessage(REASON_CODE_MAX_INFLIGHT)}. $inflight0, $inflight, ${client.getInFlightMessageCount}"
          )
        case NonFatal(e) =>
          logger.error(s"can't publish to ${p.topic}", e)
      }
      stay()

    case Event(PublishComplete(qos0), _) =>
      if (qos0) inflight0 -= 1
      else inflight -= 1
      if (pubStash.nonEmpty) self ! pubStash.dequeue()._2
//      client.unsubscribe(topic)
      stay()

    case Event(sub: Subscribe, _) =>
      context.child(urlEnc(sub.topic)) match {
        case Some(t) => t ! sub // Topic t will (only) store & watch sub.ref
        case None    => doSubscribe(sub)
      }
      stay()

    case Event(Connect, _) =>
      goto(ConnectedState)
      stay()

    // don't need handle Terminated(topicRef) in state SDisconnected
    case Event(Terminated(topicRef), _) =>
      try {
        client.unsubscribe(urlDec(topicRef.path.name))
      } catch {
        case e: MqttException
            if e.getReasonCode == REASON_CODE_CLIENT_NOT_CONNECTED => // do nothing
        case NonFatal(e) =>
          logger.error(s"can't unsubscribe from ${topicRef.path.name}", e)
      }
      stay()

    case Event(unsub: UnSubscribe, _) =>
      if (unsub.topic.nonEmpty)
        client.unsubscribe(urlDec(unsub.topic))

      stay()
  }

  onTransition { case _ =>
    stateData match {
      case _ => logger.debug("Transitioned")
    }
  }

  whenUnhandled {
    case Event(msg: Message, _) =>
//      owner ! msg
//      logger.info("Message: " + new String(msg.payload, StandardCharsets.UTF_8))
//      logger.info("url: " + urlEnc(msg.topic))
      context.child(urlEnc(msg.topic)) foreach (_ ! msg)
      stay()

    case Event(UnderlyingSubsAck(topic, fail), _) =>
      val topicSubs = subscribing.filter(_.topic == topic)
      fail match {
        case None =>
          val encTopic = urlEnc(topic)
          val topicActor = context.child(encTopic) match {
            case None =>
              val t = context.actorOf(Props[Topic](), name = encTopic)
              // TODO consider if we should use `handleChildTerminated` by override `SupervisorStrategy` instead of watch?
              context watch t
            case Some(t) => t
          }
          topicSubs.foreach { sub =>
            subscribed += sub
            topicActor ! sub
          }
        case Some(e) => // do nothing
      }
      topicSubs.foreach { sub =>
        try {
          sub.ref ! SubscribeAck(sub, fail)
          subscribing -= sub
        } catch {
          case _: Exception =>
            Future.failed(
              new Exception("Connection to Vertx Mqtt Server Failed")
            )
          case default @ _ =>
            logger.warn(
              "Default: " + default.getClass.getSimpleName + default.toString + " what"
            )
        }
      }
      stay()

    case Event(SubscriberTerminated(ref), _) =>
      subscribed.filterInPlace(_.ref != ref)

      stay()

    case Event(Disconnected, _) =>
      delayConnect()
      goto(DisconnectedState)
  }

  private def doSubscribe(sub: Subscribe): Unit = {
    // Given:
    //  val subs = subscribing.filter(_.topic == sub.topic)
    // We need call `client.subscribe` when:
    //  subs.isEmpty => subscribe first time
    //  subs.forall(_.qos < sub.qos) => resubscribe with higher qos
    if (subscribing.forall(x => x.topic != sub.topic || x.qos < sub.qos))
      try {
        client.subscribe(sub.topic, sub.qos, null, subsListener)
      } catch {
        case e: MqttException
            if e.getReasonCode == REASON_CODE_CLIENT_NOT_CONNECTED =>
          self ! Disconnected // delayConnect & goto SDisconnected
          self ! sub // stash Subscribe
        case NonFatal(e) =>
          self ! UnderlyingSubsAck(sub.topic, Some(e))
          logger.error(s"subscribe call fail ${sub.topic}", e)
        case default @ _ =>
          logger.info(
            "Default: " + default.getClass.getSimpleName + default.toString + " waiting for Vertx to start"
          )
          delayConnect()
          connectCount += 1
      }
    subscribing += sub
  }

  private def delayConnect(): Unit = {
    val delay = cfg.connectDelay(connectCount)
    logger.info(s"delay $delay before reconnect")
    startTimerWithFixedDelay("reconnect", Connect, delay)
  }

  initialize()

  self ! Connect

  onTermination { case e =>
    logger.info("stop " + e)
    val wait = 29.seconds
    Try {
      Await.result(disconnectClient(wait), wait + 1.second)
    }.recover { case NonFatal(_) =>
      client.disconnectForcibly(0, wait.toMillis)
    }.map { _ =>
      client.close(true)
    }.recover { case NonFatal(e) =>
      logger.error(e, "Can't close client")
    }.get
  }

  private def disconnectClient(wait: FiniteDuration): Future[Unit] = {
    val p = Promise[Unit]()
    client.disconnect(
      wait.toMillis,
      null,
      new IMqttActionListener {
        override def onSuccess(t: IMqttToken): Unit = p.success(())
        override def onFailure(t: IMqttToken, e: Throwable): Unit = p.failure(e)
      }
    )
    p.future
  }
}
