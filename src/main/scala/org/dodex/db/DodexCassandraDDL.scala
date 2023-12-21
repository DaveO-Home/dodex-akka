/*
   Actor to create keyspace and tables
   Note: If keyspace is created, a second cassandra session is needed to view the updated Metadata
 */

package org.dodex.db

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.DispatcherSelector
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import com.datastax.oss.driver.api.core.CqlSession
import com.typesafe.config.ConfigFactory
import org.dodex.Capsule
import org.dodex.db.DbCassandra

object DodexCassandraDDL {
  val keyspace: String = "dodex"

  def apply(): Behavior[Capsule] =
    Behaviors.setup[Capsule](context => {
      implicit val ec: scala.concurrent.ExecutionContext =
        scala.concurrent.ExecutionContext.global
      val dodexCassandraDDL = new DodexCassandraDDL(context)

      Behaviors.receiveMessage { (message) =>
        message match {
          case SetupKeyspace(sender, session) =>
            val underlying: Future[CqlSession] = session.underlying();
            underlying.onComplete {
              case Success(cqlSession) =>
                if (dodexCassandraDDL.isKeyspacePresent(keyspace, cqlSession)) {
                  // No need for another session if keyspace already defined
                  context.self ! SetupTables(sender, session)
                } else {
                  val future: Future[Done] =
                    dodexCassandraDDL.setupKeyspace(keyspace, session)
                  future.onComplete {
                    case Success(Done) =>
                      // After creating keyspace, get new session to allow updated Metadata(refreshSchema?)
                      val conf = ConfigFactory.load()
                      var dev: String = conf.getString("dev")
                      if ("true".equals(dev)) {
                        sender.tell(NewSession)
                      } else {
                        context.self ! SetupTables(sender, session)
                      }
                    case Failure(exe) =>
                      throw new Exception(exe)
                  }
                }
              case Failure(exe) =>
                throw new Exception(exe)
            }

          case SetupTables(sender, session) =>
            val future: Future[Done] =
              dodexCassandraDDL.setupTables(keyspace, session)

            if (future != null) {
              future.onComplete {
                case Success(done) =>
                  sender ! StopCreate
                case Failure(exe) =>
                  throw new Exception(exe)
              }
            } else {
              sender ! StopCreate
            }
        }
        Behaviors.same
      }
    })
}

class DodexCassandraDDL[Capsule](context: ActorContext[Capsule])
    extends AbstractBehavior[Capsule](context)
    with DbCassandra {
  implicit val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global
  val system: akka.actor.ActorSystem = akka.actor.ActorSystem()
  var log = system.log

  def setupKeyspace(
      keySpace: String,
      cassandraSession: CassandraSession
  ): Future[Done] = {
    val keyspace: Future[Done] = cassandraSession.executeDDL(
      getCreateKeyspace()
    )
    keyspace
  }

  def setupTables(
      keySpace: String,
      cassandraSession: CassandraSession
  ): Future[Done] = {
    val future: Future[CqlSession] = cassandraSession.underlying()
    var createdFuture: Future[Done] = null

    future.onComplete {
      case Success(cqlSession) =>
        log.info(s"The Cassandra Keyspace: $keySpace")
        createdFuture = createTables(cassandraSession, cqlSession, keySpace)
      case Failure(exe) =>
        val msg = exe.getMessage()
        log.error(s"The Cassandra Keyspace Failure: $msg")
        cassandraSession.close(ec)
        throw new Exception(exe)
    }

    Await.result(future, 2000.milliseconds)
    createdFuture
  }

  def createTables(
      cassandraSession: CassandraSession,
      cqlSession: CqlSession,
      keySpace: String
  ): Future[Done] = {
    var future: Future[Done] = null

    while (tables.hasNext) {
      val table = tables.next()

      if (!isTablePresent(keySpace, table, cqlSession)) {
        log.info("Table Created: {}", table)
        future = cassandraSession.executeDDL(getCreateTable(table))
      }
    }
    future
  }

  def isKeyspacePresent(keySpace: String, cqlSession: CqlSession): Boolean = {
    val isPresent: Boolean = cqlSession
      .getMetadata()
      .getKeyspace(keySpace)
      .isPresent()
    isPresent
  }

  def isTablePresent(
      keySpace: String,
      table: String,
      cqlSession: CqlSession
  ): Boolean = {
    val isPresent: Boolean = cqlSession
      .getMetadata()
      .getKeyspace(keySpace)
      .get()
      .getTable(table)
      .isPresent()
    isPresent
  }

  override def onMessage(msg: Capsule): Behavior[Capsule] = {
    Behaviors.unhandled
  }
}
