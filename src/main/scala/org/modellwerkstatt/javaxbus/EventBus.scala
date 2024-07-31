/*
 * Converted from: https://github.com/danielstieger/javaxbus to scala
 * EventBus.java
 * <daniel.stieger@modellwerkstatt.org>
 *
 *
 * VertX 4 EventBus client in plain java. This is the public API of this eventbus client.
 *
 */

package org.modellwerkstatt.javaxbus

import mjson.Json
import EventBus.*

import scala.compiletime.uninitialized

object EventBus {

  val VERSION: String = "0.9 C"

  val USE_NIO: Boolean = true

  def create(hostname: String, port: Int): EventBus = {
    val bus: EventBus = new EventBus()
    bus.init(hostname, port)
    bus
  }

}

class EventBus {

  private var communicatorThread: Thread = uninitialized

  private var com: EventBusRunnable = uninitialized

  def consumer(address: String, handler: ConsumerHandler): Unit = {
    if (com == null) {
      throw new IllegalStateException("Eventbus not initialized.")
    }
    com.registerHander(address, handler, true)
  }

  def unregisgterConsumer(address: String, handler: ConsumerHandler): Unit = {
    if (com == null) {
      throw new IllegalStateException("Eventbus not initialized.")
    }
    com.unRegisterHander(address, handler)
  }

  def addErrorHandler(handler: ErrorHandler): Unit = {
    if (com == null) {
      throw new IllegalStateException("Eventbus not initialized.")
    }
    com.addErrorHandler(handler)
  }

  def removeErrorHandler(handler: ErrorHandler): Unit = {
    if (com == null) {
      throw new IllegalStateException("Eventbus not initialized.")
    }
    com.addErrorHandler(handler)
  }

  def send(adr: String, content: Json): Unit = {
    if (com == null) {
      throw new IllegalStateException("Eventbus not initialized.")
    }
    com.sendToStream(false, adr, content, null)
  }

  def send(adr: String, content: Json, replyHandler: ConsumerHandler): Unit = {
    if (com == null) {
      throw new IllegalStateException("Eventbus not initialized.")
    }
    com.sendToStream(false, adr, content, replyHandler)
  }

  def publish(adr: String, content: Json): Unit = {
    if (com == null) {
      throw new IllegalStateException("Eventbus not initialized.")
    }
    com.sendToStream(true, adr, content, null)
  }

  def isConnected: Boolean = {
    if (com == null) {
      // was not initialiized or maybe already closed...
      return false
    }
    com.isConnected()
  }

  def close(): Unit = {
    if (com == null) {
      throw new IllegalStateException("Eventbus not initialized.")
    }
    com.shutdown()
    // this will probably not work on socket i/o
    communicatorThread.interrupt()
    // however, close will shutown the thread
    com.closeCon()
    communicatorThread = null
    com = null
  }

  def setUnderTestingMode(): Unit = {
    if (com == null) {
      throw new IllegalStateException("Eventbus not initialized.")
    }
    com.setUnderTest()
  }

  private def init(hostname: String, port: Int): Unit = {
    com = new EventBusRunnable()
    com.init(hostname, port)
    communicatorThread = new Thread(com)
    communicatorThread.setName("Akka EventBus Recv.")
    communicatorThread.setDaemon(true)
    communicatorThread.start()
  }

}
