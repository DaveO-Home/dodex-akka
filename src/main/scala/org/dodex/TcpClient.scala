package org.dodex

import akka.Done
import akka.actor.Actor
import akka.actor.ActorLogging
import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Cancellable
import akka.actor.CoordinatedShutdown
import akka.actor.typed.DeathPactException
import akka.actor.Props
import akka.actor.Terminated
import akka.actor.UnhandledMessage
import akka.actor.typed.scaladsl.Behaviors
import akka.io.IO
import akka.io.Tcp
import akka.io.Tcp.Register
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import akka.util.ByteString
import akka.util.Timeout
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import org.dodex.ex.Exchanger

import java.net.ConnectException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.collection.immutable.Seq

abstract class Capsule
case class SessionCassandra(
    sender: ActorRef,
    cassandraSession: CassandraSession
) extends Capsule

case class TcpActor(
    tcpActor: ActorRef
) extends Capsule

case class ReturnData(
    json: mjson.Json
) extends Capsule
case object CloseSession extends Capsule

object TcpClient {
  val system = ActorSystem("actor-system")
  var listener: ActorRef = null
  var client: ActorRef = null
  var confOnly = false
  var file = "./src/main/resources/application.json"
  val waitFor: Promise[Null] = Promise[Null]()

  @main
  def TcpClientMain(args: String*): Any = {
    if (args != null) {
      for arg <- args do {
        val argVal = arg.split("=")
        if (argVal.length == 2)
          argVal(0) match {
            case "conf" => confOnly = "true" == argVal(1)
            case "file" => file = argVal(1)
            case _      =>
          }
      }
      waitFor completeWith Future { null }
    }
  }

  implicit val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global
  @volatile var log = system.log

  waitFor.future.onComplete {
    case Success(result) =>
      val DEV: String = "true"
      val conf: Config = ConfigFactory.load()

      // dev is set to true in "sbt.build", for Metals debugging see launch.json
      val dev: String = conf.getString("dev")
      val host: String =
        if (DEV == dev) conf.getString("event.bus.dev.host")
        else conf.getString("event.bus.host")
      val port: Int =
        if (DEV == dev) conf.getInt("event.bus.dev.port")
        else conf.getInt("event.bus.port")

      @volatile var can: Cancellable = null

      can = CoordinatedShutdown(system).addCancellableTask(
        // CoordinatedShutdown.PhaseBeforeActorSystemTerminate,
        CoordinatedShutdown.PhaseBeforeServiceUnbind,
        "close"
      ) { () =>
        Future {
          println("TcpClient Terminating System")
          can.cancel() // do only once
          Done
        }
      }
      // Only saving the configuration for the "assembly" task (fat jar)
      if (confOnly) {
        new java.io.PrintWriter(file) {
          write(conf.atKey("akka").toString())
          close()
        }
        system.terminate()
        log.warning("{} written", file)
      } else {
        // Tcp client passes Cassandra work to listener
        listener = system.actorOf(Props[Exchanger](), "listener")

        // Startup the Tcp Client
        client = system.actorOf(
          Client.props(new InetSocketAddress(host, port), listener),
          "client"
        )
      }
    case Failure(err) =>
      val msg = err.getMessage
      println("TcpClient startup failed: " + msg)
  }

  def stopTcpClient(): Unit = {
    system.stop(client)
    Behaviors.stopped
  }
}

object Client {
  def props(
      remote: InetSocketAddress,
      replies: ActorRef
  ): Props =
    Props(classOf[Client], remote, replies)
}

class Client(
    remote: InetSocketAddress,
    listener: ActorRef
) extends Actor {
  import Tcp._
  import context.system
  import akka.pattern.retry
  import scala.concurrent.duration._
  import scala.language.postfixOps
  import org.dodex.util.Limits

  implicit val scheduler: akka.actor.Scheduler =
    system.classicSystem.scheduler
  implicit val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global
  val handler: ActorRef = listener
  @volatile var log = system.log
  // val log = org.slf4j.LoggerFactory.getLogger("logging.service")
  @volatile var failCount = 0
  @volatile var connected = false
  var connectLimit: Int = Limits.connectLimit // 3
  var hourLimit: Int = 360
  var dayLimit: Int = 288
  var shuttingDown: Boolean = false
  // Connect to Vertx
  IO(Tcp) ! Connect(remote)

  // Tcp.SO.KeepAlive(true)

  def receive: PartialFunction[Any, Unit] = {
    case CommandFailed(_: Connect) =>
      // listener ! "connect failed"
      if (!shuttingDown) {
        shuttingDown = true
        restartTcpOnFailure(remote)
      }

    case c @ Connected(remote, local) =>
      // Startup Vertx handshake and Cassandra
      listener ! c

      // TCP ActorRef
      val connection = sender()

      // Telling TCP to use this actor's Received
      connection ! Register(self, true, false)

      // Client takes on the TCP receiving functionality
      context.become {
        case data: ByteString =>
          connection ! Write(data)
        case CommandFailed(w: Write) =>
          // O/S buffer was full
          listener ! "write failed"
        case Received(data) =>
          // Pass TCP data to exchanger
          listener ! data
        case "close" =>
          connection ! Close
        case _: ConnectionClosed =>
          if (!shuttingDown) {
            shuttingDown = true
            listener ! "connection closed"
            listener ! "stop dodex"
            restartTcpOnFailure(remote)
          }

        case default @ _ =>
          log.warning(
            "Default: {} : {} -- {}" + default.getClass.getSimpleName,
            default,
            "waiting for Vertx to start"
          )
      }
  }

  def futureToAttempt(remote: InetSocketAddress): Future[Int] = {
    if (failCount < Limits.connectLimit) {
      failCount += 1
      try {
        println("FailCount=" + failCount)

        TcpClient.stopTcpClient()
        // TcpClientDodex.TcpClient().stopTcpClient()
        system.actorOf(
          Client.props(
            new InetSocketAddress(
              remote.getAddress.toString.substring(1),
              remote.getPort
            ),
            handler
          ),
          "client" + failCount
        )
        failCount = connectLimit
        connected = true
        shuttingDown = false
      } catch {
        case _: ConnectException => {
          if (failCount == Limits.connectLimit) {
            Future.failed(
              new ConnectException("Connection to Vertx Event Bus Failed")
            )
          }
        }
        case default @ _ =>
          log.warning(
            "Default: {} : {}" + default.getClass.getSimpleName,
            default
          )
      }
      Future.failed(
        new IllegalStateException("Connection to Vertx Event Bus Failed")
      )
    } else {
      if (connected) Future.successful(failCount)
      else
        Future.failed(
          new ConnectException(
            "Connection to Vertx Event Bus Failed: " + failCount
          ).getCause
        )
    }
  }

  // Doing this for Development - when vertx goes down the client may reconnect
  def restartTcpOnFailure(remote: InetSocketAddress): Unit = {
    val retried = retry(
      () => futureToAttempt(remote),
      attempts = Limits.connectLimit,
      Limits.interval milliseconds
    )
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
