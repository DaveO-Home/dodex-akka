package org.modellwerkstatt.javaxbus

import mjson.Json

class VertXProtoMJson {

  def send(adr: String, msg: Json, replyAddr: String): Json = {
    val json: Json = Json.`object`()
    json.set("type", "send")
    json.set("address", adr)
    json.set("body", msg)
    if (replyAddr != null) {
      json.set("replyAddress", replyAddr)
    }
    json
  }

  def publish(adr: String, msg: Json, replyAddr: String): Json = {
    val sendJson: Json = send(adr, msg, replyAddr)
    sendJson.set("type", "publish")
    sendJson
  }

  def register(adr: String): Json = {
    val json: Json = Json.`object`()
    json.set("type", "register")
    json.set("address", adr)
    json
  }

  def unregister(adr: String): Json = {
    val json: Json = Json.`object`()
    json.set("type", "unregister")
    json.set("address", adr)
    json
  }

  def ping(): Json = Json.`object`().set("type", "ping")

  def prepareMessageToDeliver(`type`: String, json: Json): Message = {
    var msgToDeliver: Message = null
    // both might have a replyAddr ?
    val reply: String =
      if (json.has("replyAddress")) json.at("replyAddress").asString()
      else null
    if ("message" == `type`) {
      // message has always address, body and send flag.
      val address: String = json.at("address").asString()
      val body: Json = json.at("body")
      val sended: Boolean = json.at("send").asBoolean()
      msgToDeliver = new Message(address, sended, reply, body)
    } else {
      // might not have a address
      val address: String =
        if (json.has("address")) json.at("address").asString() else null
      val failMsg: String = json.at("message").asString()
      val failCode: String =
        if (json.has("failureCode")) json.at("failureCode").asString() else ""
      val failType: String =
        if (json.has("failureType")) json.at("failureType").asString() else ""
      // unsure about that one.
      val send: Boolean =
        if (json.has("send")) json.at("send").asBoolean() else true
      msgToDeliver =
        new Message(address, send, reply, failMsg, failCode, failType)
    }
    msgToDeliver
  }

}

