package org.dodex.db

import akka.Done
import akka.actor.{ActorRef, Cancellable, CoordinatedShutdown}
import akka.actor.typed.*
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.{CassandraSession, CassandraSessionRegistry}
import com.datastax.oss.driver.api.core.CqlSession
import com.typesafe.config.{Config, ConfigFactory}
import mjson.Json
import org.dodex.{Capsule, CloseSession, SessionCassandra}
import org.dodex.ex.{DodexData, ShutDown}
import org.dodex.util.Limits

import java.io.File
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scribe.Logger

import scala.compiletime.uninitialized

case object NewSession extends Capsule
case object StopCreate extends Capsule
case class SetupKeyspace(
    sender: akka.actor.typed.ActorRef[Capsule],
    cassandraSession: CassandraSession
) extends Capsule
case class SetupTables(
    sender: akka.actor.typed.ActorRef[Capsule],
    cassandraSession: CassandraSession
) extends Capsule
case class DodexDml(
    sender: akka.actor.ActorRef,
    json: mjson.Json,
    cassandraSession: CassandraSession
) extends Capsule

object DodexCassandra {
  var log: Logger = Logger("DodexCassandra")
  var cassandraSession: CassandraSession = uninitialized
  def apply(): Behavior[Capsule] =
    Behaviors.setup[Capsule](context => {
      // implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
      val dodexCassandra = new DodexCassandra(context)
      implicit val executionContext: ExecutionContext =
        context.system.dispatchers.lookup(
          DispatcherSelector.fromConfig("cassandra-blocking-dispatcher")
        )
      var db: akka.actor.typed.ActorRef[Capsule] = null
      var dml: akka.actor.typed.ActorRef[Capsule] = null
      Behaviors
        .receive[Capsule] { (context, message) =>
          message match {
            case SessionCassandra(sender, session) => //sender @ (_:ActorRef) =>
              startCassandraDatabase()
              cassandraSession = dodexCassandra.initCassandra()
              sender ! SessionCassandra(sender, cassandraSession)

              db = context.spawn(DodexCassandraDDL(), "setup-database")
              context.watch(db)

              db ! SetupKeyspace(context.self, cassandraSession)

            case NewSession =>
              val future: Future[Done] =
                cassandraSession.close(executionContext)
              future.onComplete {
                case Success(Done) =>
                  cassandraSession = dodexCassandra.initCassandra()
                  db ! SetupTables(context.self, cassandraSession)
                case Failure(exe) =>
                  throw new Exception(exe)
              }

            case StopCreate =>
              dml = context.spawn(DodexCassandraDML(), "dml-database")
              context.stop(db)

            case DodexData(sender, json) =>
              if (dml != null) {
                cassandraSession.underlying().onComplete {
                  case Success(cqlSession: CqlSession) =>
                    if (cqlSession.isClosed)
                      cassandraSession = dodexCassandra.initCassandra()
                    dml ! DodexDml(sender, json, cassandraSession)
                  case Failure(exe) =>
                    throw new Exception(exe)
                }
              }
            case CloseSession =>
              if (cassandraSession != null)
                cassandraSession.close(executionContext)
            case ShutDown =>
              if (dml != null) {
                context.stop(dml)
              }
              context.self ! CloseSession
              Behaviors.stopped
            case default @ (_: Any) =>
              log.warn("Default: " + default.getClass.getSimpleName)
          }
          Behaviors.same
        }
        .receiveSignal {
          case (context, Terminated(ref)) =>
            log.info("Actor " +  ref.path.name + " finished & stopped")
            Behaviors.same
        }
    })

  private def startCassandraDatabase()(implicit ec: ExecutionContext): Unit = {
    val conf = ConfigFactory.load()
    val dev: String = conf.getString("dev")
    if ("true".equals(dev)) {
      val databaseDirectory = new File("target/cassandra-db")
      var port: Int = 9042
      var clean: Boolean = false

//      CassandraLauncher.start(
//        databaseDirectory,
//        CassandraLauncher.DefaultTestConfigResource,
//        clean = clean,
//        port = port
//      )
    }
  }
}

class DodexCassandra(context: ActorContext[Capsule])
    extends AbstractBehavior[Capsule](context) {
  

  import scala.language.postfixOps
  val system: akka.actor.ActorSystem = akka.actor.ActorSystem()
  var log: Logger = Logger("DodexCassandra")
  // implicit val ec: ExecutionContext = system.classicSystem.getDispatcher
  implicit val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global
  implicit val scheduler: akka.actor.Scheduler =
    system.classicSystem.scheduler
  // implicit val materializer: akka.stream.ActorMaterializer = ActorMaterializer()

  val DEV: String = "true"
  var connectLimit: Int = Limits.connectLimit // 3
  var hourLimit: Int = 360
  var dayLimit: Int = 288
  val conf: Config = ConfigFactory.load()
  var dev: String = conf.getString("dev")

  @volatile var failCount = 0
  @volatile var connected = false
  @volatile var can: Cancellable =
    CoordinatedShutdown(system).addCancellableTask(
      CoordinatedShutdown.PhaseBeforeServiceUnbind,
      "cleanup"
    ) { () =>
      Future {
        log.info("Cassandra Terminating Cassandra")
        system.terminate()
        Done
      }
    }

  def initCassandra(): CassandraSession = {
    var sessionSettings: CassandraSessionSettings = null
    dev = "prod"
    if (DEV.equals(dev)) {
      sessionSettings = CassandraSessionSettings(
        "dodex-dev-with-akka-discovery"
      ) // embedded database
    } else {
      sessionSettings = CassandraSessionSettings(
        "dodex-with-akka-discovery"
      ) // Networked Database
    }
    val cassandraSession: CassandraSession =
      CassandraSessionRegistry.get(system).sessionFor(sessionSettings)
    cassandraSession
  }

  override def onMessage(msg: Capsule): Behavior[Capsule] = {
    Behaviors.unhandled
  }

  override def onSignal: PartialFunction[Signal, Behavior[Capsule]] = {
    case PostStop =>
      log.warn("Dodex Application stopped")
      this
  }
}
