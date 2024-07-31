package com.sandinh.paho.akka

import org.eclipse.paho.client.mqttv3.{IMqttActionListener, IMqttToken}
import MqttPubSub.logger
import akka.actor.ActorRef

import scala.util.control.NonFatal

private class SubscribeListener(owner: ActorRef) extends IMqttActionListener {
  def onSuccess(asyncActionToken: IMqttToken): Unit = {
    val topic = asyncActionToken.getTopics()(0) //getTopics always has len == 1
    val code = asyncActionToken.getGrantedQos()(0)

    logger.debug(s"subscribe success $topic -- $code")

    try {
      owner ! UnderlyingSubsAck(topic, None)
    } catch {
      case NonFatal(e) =>
        logger.error(s"can't connect to handled", e)
//        delayConnect()
    }
  }
  def onFailure(asyncActionToken: IMqttToken, e: Throwable): Unit = {
    val topic = asyncActionToken.getTopics()(0) //getTopics always has len == 1
    logger.error(s"subscribe fail $topic", e)
    owner ! UnderlyingSubsAck(topic, Some(e))
  }
}
