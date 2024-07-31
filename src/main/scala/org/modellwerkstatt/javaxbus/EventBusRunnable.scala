package org.modellwerkstatt.javaxbus

import mjson.Json
import org.modellwerkstatt.javaxbus.EventBusRunnable.*

import java.io.*
import java.lang.Thread.interrupted
import java.net.{ConnectException, SocketException}
import java.nio.channels.{ClosedByInterruptException, ClosedChannelException}
import java.util
import java.util.{ArrayList, HashMap, List}
import scala.beans.BooleanBeanProperty
import scala.compiletime.uninitialized

object EventBusRunnable {

  private val RECON_TIMEOUT: Int = 10000

  private val FAST_RECON_TIMEOUT: Int = 500

  private val TEMP_HANDLER_SIGNATURE: String = "__MODWERK_HC__"

}

class EventBusRunnable extends Runnable {

  private var hostname: String = uninitialized

  private var port: Int = uninitialized

  private var io: IOSocketService = uninitialized

  @BooleanBeanProperty
  var upNRunning: Boolean = false

  @volatile private var stillConnected: Boolean = false

  private val proto: VertXProtoMJson = new VertXProtoMJson()

  private val consumerHandlers: util.HashMap[String, util.List[ConsumerHandler]] =
    new util.HashMap[String, util.List[ConsumerHandler]]()

  private val errorHandler: util.List[ErrorHandler] = new util.ArrayList[ErrorHandler]()

  private var underTest: Boolean = false

  def sendToStream(
      publish: Boolean,
      adr: String,
      msg: Json,
      replyHandler: ConsumerHandler
  ): Unit = {
    var replyAdr: String = null
    if (replyHandler != null) {
      replyAdr = adr + TEMP_HANDLER_SIGNATURE + replyHandler.hashCode
      registerHander(replyAdr, replyHandler, false)
    }
    if (publish) {
      io.writeToStream(proto.publish(adr, msg, replyAdr))
    } else {
      io.writeToStream(proto.send(adr, msg, replyAdr))
    }
  }

  def registerHander(
      adr: String,
      handler: ConsumerHandler,
      registerWithServer: Boolean
  ): Unit = {
    this.synchronized {
      if (!consumerHandlers.containsKey(adr)) {
        consumerHandlers.put(adr, new util.ArrayList[ConsumerHandler]())
      }
      val listOfHandlers: util.List[ConsumerHandler] = consumerHandlers.get(adr)
      listOfHandlers.add(handler)
      if (listOfHandlers.size == 1 && registerWithServer) {
        io.writeToStream(proto.register(adr))
      }
    }
  }

  def unRegisterHander(adr: String, handler: ConsumerHandler): Unit = {
    this.synchronized {
      if (!consumerHandlers.containsKey(adr)) {
        throw new IllegalStateException("No handlers registered for adr " + adr)
      }
      val existingHandlers: util.List[ConsumerHandler] = consumerHandlers.get(adr)
      if (!existingHandlers.contains(handler)) {
        throw new IllegalStateException("Handler not registered for adr " + adr)
      }
      existingHandlers.remove(handler)
      if (existingHandlers.size == 0) {
        io.writeToStream(proto.unregister(adr))
      }
    }
  }

  def addErrorHandler(handler: ErrorHandler): Unit = {
    this.synchronized {
      if (errorHandler.contains(handler)) {
        throw new IllegalStateException(
          "You should not register this handler twice."
        )
      }
      errorHandler.add(handler)
    }
  }

  def removeErrorHandler(handler: ErrorHandler): Unit = {
    this.synchronized {
      if (!errorHandler.contains(handler)) {
        throw new IllegalStateException(
          "The given handler was never registered....."
        )
      }
      errorHandler.remove(handler)
    }
  }

  private def dispatchMessage(adr: String, msg: Message): Unit = {
    this.synchronized {
      if (!consumerHandlers.containsKey(adr)) {
        throw new IllegalStateException(
          "No handlers registered for " + adr + " but msg " + msg.toString +
            " received."
        )
      }
      val handlers: util.List[ConsumerHandler] = consumerHandlers.get(adr)
      // pre Scala 2.13
      // for (h <- handlers) {
      //   h.handle(msg)
      // }
      handlers.forEach(_.handle(msg))

      if (adr.contains(TEMP_HANDLER_SIGNATURE)) {
        consumerHandlers.get(adr).clear()
      }
    }
  }

  private def dispatchErrorFromBus(msg: Message): Unit = {
    this.synchronized {
      if (errorHandler.size == 0) {
        System.err.println(msg.toString)
      } else {
        // Pre Scala 2.13
        // for (e <- errorHandler) {
        //   e.handleMsgFromBus(stillConnected, upNRunning, msg)
        // }
        errorHandler.forEach(
          _.handleMsgFromBus(stillConnected, upNRunning, msg)
        )
      }
    }
  }

  private def dispatchException(exception: Exception): Unit = {
    this.synchronized {
      if (errorHandler.size == 0) {
        exception.printStackTrace()
      } else {
        // Pre Scala 2.13
        // for (e <- errorHandler) {
        //   e.handleException(stillConnected, upNRunning, exception)
        // }
        errorHandler.forEach(
          _.handleException(stillConnected, upNRunning, exception)
        )
      }
    }
  }

  override def run(): Unit = {
    upNRunning = true
    while (!interrupted() && upNRunning) try if (stillConnected) {
      val msg: Json = io.readFormStream()
      val msgType: String = msg.at("type").asString()
      if ("pong" == msgType) {
        // nice one
        println("pong worked")
      } else if ("message" == msgType) {
        if (upNRunning) {
          val msgToSend: Message = proto.prepareMessageToDeliver(msgType, msg)
          dispatchMessage(msgToSend.address, msgToSend)
        }
      } else if ("err" == msgType) {
        // is there an address set?
        if (upNRunning && msg.has("address")) {
          val msgToSend: Message = proto.prepareMessageToDeliver(msgType, msg)
          dispatchMessage(msgToSend.address, msgToSend)
        } else {
          val msgToSend: Message = proto.prepareMessageToDeliver(msgType, msg)
          // call error Handler
          dispatchErrorFromBus(msgToSend)
        }
      }
    } else {
      tryReconnect()
    } catch {
      case e: SocketException =>
        stillConnected = false
        if (upNRunning) {
          dispatchException(e)
        }

      case e: ClosedByInterruptException =>
        stillConnected = false
        if (upNRunning) {
          dispatchException(e)
        }

      case e: ClosedChannelException =>
        stillConnected = false
        if (upNRunning) {
          dispatchException(e)
        }

      case e: EOFException =>
        stillConnected = false
        dispatchException(e)

      case e: IOException =>
        stillConnected = false
        dispatchException(e)

      case e: Exception =>
        stillConnected = false
        dispatchException(e)

    }
  }

  private def tryReconnect(): Unit = {
    try closeCon()
    catch {
      case e: Exception => println("Close Connection Failure")
    }
    try {
      Thread.sleep(if (underTest) FAST_RECON_TIMEOUT else RECON_TIMEOUT)
      // shutdown while sleeping ?
      if (upNRunning) {
        initCon()
        // if that is successfull, we have to register handlers ...
        // Pre Scala 2.13
        // synchronized(this) {
        // for (adr <- consumerHandlers.keySet) {
        //   io.writeToStream(proto.register(adr))
        // }
        this.synchronized {
          consumerHandlers.keySet.forEach { adr =>
            io.writeToStream(proto.register(adr))
          }
        }
      }
    } catch {
      case e: IOException =>
        stillConnected = false
        dispatchException(e)

      case e: RuntimeException =>
        if (
          e.getCause != null && e.getCause.getClass == classOf[
            ConnectException
          ]
        ) {
          // println(e.getMessage())
        } else {
          stillConnected = false
          dispatchException(e)
        }

      case e: InterruptedException =>
      // println(e.getMessage())

      case e: Exception =>
        stillConnected = false
        dispatchException(e)

    }
  }

  def isConnected(): Boolean = stillConnected

  def init(hostname: String, port: Int): Unit = {
    this.hostname = hostname
    this.port = port
    initCon()
  }

  private def initCon(): Unit = {
    io =
      if (EventBus.USE_NIO) new NonBlockingIO()
      else new TraditionalSocketIO()
    io.init(hostname, port)

    io.writeToStream(proto.ping())

    stillConnected = true
  }

  def setUnderTest(): Unit = {
    underTest = true
  }

  def shutdown(): Unit = {
    this.synchronized {
      upNRunning = false
      if (stillConnected) {
        // Pre Scala 2.13
//       for (adr <- consumerHandlers.keySet) {
//         if (adr.contains(TEMP_HANDLER_SIGNATURE)) {
// do not unregister this one, since this is only a reply handler
//           println("stuff")
//         } else {
//           io.writeToStream(proto.unregister(adr))
//         }
        consumerHandlers.keySet.forEach { adr =>
          if (adr.contains(TEMP_HANDLER_SIGNATURE)) {
            // do not unregister this one, since this is only a reply handler
          } else {
            io.writeToStream(proto.unregister(adr))
          }
          consumerHandlers.get(adr).clear()
        }
      }
      consumerHandlers.clear()
      // remove all error handlers
      errorHandler.clear()
    }
  }

  def closeCon(): Unit = {
    this.synchronized {
      if (stillConnected) {
        stillConnected = false
        io.close()
      }
    }
  }

}
