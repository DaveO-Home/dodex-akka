package com.sandinh.paho.akka

import akka.actor.ActorRef
import org.eclipse.paho.client.mqttv3.{IMqttDeliveryToken, MqttCallback, MqttMessage}
import MqttPubSub.logger

import java.nio.charset.StandardCharsets

private class PubSubMqttCallback(owner: ActorRef) extends MqttCallback {
  def connectionLost(cause: Throwable): Unit = {
    logger.error("connection lost", cause)
    owner ! Disconnected
  }

  /** only logging */
  def deliveryComplete(token: IMqttDeliveryToken): Unit = {
    logger.debug(
      "delivery complete " + java.util.Arrays.toString(
        token.getTopics.asInstanceOf[Array[AnyRef]]
      )
    )
  }
  def messageArrived(topic: String, message: MqttMessage): Unit = {
    logger.debug(s"message arrived $topic {}", new String(message.getPayload,StandardCharsets.UTF_8))
    owner ! new Message(topic, message.getPayload)
  }
}
