package org.dodex.db

import java.io.File

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.ActorRef
import akka.actor.Cancellable
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.DispatcherSelector
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.adapter._
import akka.pattern.retry
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.stream.ActorMaterializer
import akka.stream.Materializer
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.cql._
import com.datastax.oss.driver.api.core.metadata.Metadata
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import mjson.Json
import org.dodex.Capsule
import org.dodex.CloseSession
import org.dodex.SessionCassandra
import org.dodex.db.DbCassandra
import org.dodex.ex.DodexData
import org.dodex.ex.ShutDown
import org.dodex.util.Limits
import org.modellwerkstatt.javaxbus._

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
  var cassandraSession: CassandraSession = null
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
              sender ! new SessionCassandra(sender, cassandraSession)

              db = context.spawn(DodexCassandraDDL(), "setup-database")
              context.watch(db)

              db ! new SetupKeyspace(context.self, cassandraSession)

            case NewSession =>
              val future: Future[Done] =
                cassandraSession.close(executionContext)
              future.onComplete {
                case Success(Done) =>
                  cassandraSession = dodexCassandra.initCassandra()
                  db ! new SetupTables(context.self, cassandraSession)
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
                    if (cqlSession.isClosed())
                      cassandraSession = dodexCassandra.initCassandra()
                    dml ! new DodexDml(sender, json, cassandraSession)
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
              context.log.warn("Default: " + default.getClass.getSimpleName)
          }
          Behaviors.same
        }
        .receiveSignal {
          case (context, Terminated(ref)) =>
            context.log.info("Actor '{}' finished & stopped", ref.path.name)
            Behaviors.same
        }
    })

  def startCassandraDatabase()(implicit ec: ExecutionContext): Unit = {
    val conf = ConfigFactory.load()
    var dev: String = conf.getString("dev")
    if ("true".equals(dev)) {
      val databaseDirectory = new File("target/cassandra-db")
      var port: Int = 9042;
      var clean: Boolean = false

      CassandraLauncher.start(
        databaseDirectory,
        CassandraLauncher.DefaultTestConfigResource,
        clean = clean,
        port = port
      )
    }
  }
}

class DodexCassandra(context: ActorContext[Capsule])
    extends AbstractBehavior[Capsule](context) {
  import scala.language.postfixOps

  implicit val system = akka.actor.ActorSystem()
  // val log = org.slf4j.LoggerFactory.getLogger("logging.service")
  // @volatile var log = context.log
  var log = system.log

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
  val conf = ConfigFactory.load()
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
      context.log.warn("Dodex Application stopped")
      this
  }
}
