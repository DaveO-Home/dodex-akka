package org.modellwerkstatt.javaxbus

import mjson.Json

trait IOSocketService {

  def init(hostname: String, port: Int): Unit

  def writeToStream(msg: Json): Unit

  def readFormStream(): Json

  def close(): Unit

}
