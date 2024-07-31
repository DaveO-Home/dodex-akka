package org.dmo

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.util.Failure
import scala.util.Success

import akka.Done
//import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.typed.scaladsl.adapter._
import akka.persistence.cassandra.testkit.CassandraLauncher
import akka.stream.alpakka.cassandra.CassandraSessionSettings
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import akka.stream.alpakka.cassandra.scaladsl.CassandraSessionRegistry
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.testkit.TestKitBase
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.cql.SimpleStatement
import com.datastax.oss.driver.api.core.cql._
import com.datastax.oss.driver.internal.core.cql.DefaultPreparedStatement
import org.dodex.db.DbCassandra
import org.dodex.db.DbQueryBuilder
import org.scalatest.BeforeAndAfterEach
//import org.scalatest.MustMatchers._
import org.scalatest._
import org.scalatest.funsuite.AsyncFunSuite
import org.scalatest.wordspec.AnyWordSpecLike
import scribe.Logger

class CassandraSpec
    extends ScalaTestWithActorTestKit
    with AnyWordSpecLike
    with BeforeAndAfterEach {
  //
}
// Test Cassandra Usage
class CassandraDodex
    extends AsyncFunSuite // AnyFunSuite
    with BeforeAndAfterAll
    // with LogCapturing
    with DbCassandra
    with DbQueryBuilder {
  var version: Future[String] = null
  implicit val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global
  val system = akka.actor.ActorSystem()
  var sessionSettings: CassandraSessionSettings = CassandraSessionSettings(
    "dodex-dev-with-akka-discovery"
  )
  var cassandraSession: CassandraSession = null
  var log: Logger = Logger("CassandraDodex")
  override def beforeAll(): Unit = {
    implicit val system = akka.actor.ActorSystem()
    val databaseDirectory = new File("target/cassandra-test-db")
    var port: Int = 9042;
    var clean: Boolean = true

//    CassandraLauncher.start(
//      databaseDirectory,
//      CassandraLauncher.DefaultTestConfigResource,
//      clean = clean,
//      port = port
//    )
    cassandraSession =
      CassandraSessionRegistry.get(system).sessionFor(sessionSettings)
    version = cassandraSession
      .select("SELECT release_version FROM system.local;")
      .map(_.getString("release_version"))
      .runWith(Sink.head)

    super.beforeAll()
  }
  override protected def afterAll(): Unit = {
    CassandraLauncher.stop()
    super.afterAll()
  }

  test("cassandra version is at least 3") {
    Await.result(version, 5.seconds)
    version.onComplete {
      case Success(result) => {

        log.info("The Cassandra Version: {}", result)
        println(s"The Cassandra Version: $result")
        val floatResult: Float =
          result.substring(0, result.lastIndexOf(".")).toFloat
        assert(floatResult > 3.0)
      }
      case Failure(exe) => {
        val msg = exe.getMessage()
        log.error(s"The Cassandra Version: $msg")
        throw new Exception(exe)
      }
    }
    Future { assert(version.isCompleted == true) }
  }

  test("cassandra keyspace created") {
    val keyspace = setupKeyspace("dodex", cassandraSession)
    Await.result(keyspace, 3.seconds)
    assert(keyspace.isCompleted == true)
  }

  test("cassandra tables created") {
    cassandraSession.close(ec)
    cassandraSession =
      CassandraSessionRegistry.get(system).sessionFor(sessionSettings)

    val tables = createTables("dodex", cassandraSession)

    tables.onComplete {
      case Success(done) =>
        assert(done == Done)
      case Failure(exe) =>
        assert(exe.getMessage() == null)
    }

    Await.result(tables, 8.seconds)
    Future { assert(tables.isCompleted == true) }
  }

  test("insert user_message") {
    val insertUser: String = getUserInsert()

    var future: Future[PreparedStatement] = insertUsers(insertUser)
    completeUserInsert(future, "handle", "pass", "127.0.0.1")
    future = insertUsers(insertUser)
    assert(future != null)

    completeUserInsert(future, "handle2", "pass2", "127.0.0.2")

    Await.result(future, 3.seconds)
    assert(future.isCompleted == true)
  }

  test("insert message_user") {
    val insertMessage: String = getMessageInsert()

    var future: Future[PreparedStatement] = insertMessages(insertMessage)
    completeMessageInsert(
      future,
      "handle",
      "pass",
      "The Big Message",
      "handle2"
    )

    future = insertMessages(insertMessage)
    assert(future != null)

    completeMessageInsert(
      future,
      "handle",
      "pass",
      "The Big Message2",
      "handle2"
    )

    Await.result(future, 3.seconds)
    assert(future.isCompleted == true)
  }

  test("select undelivered messages for user") {
    implicit val system = akka.actor.ActorSystem()
    val selectUndelivered: String = getSelectUndelivered()

    var messages: Future[Seq[Row]] = cassandraSession
      .select(selectUndelivered, "handle", "pass")
      .runWith(Sink.seq)

    messages.onComplete {
      case Success(data) =>
        assert(data.size > 1)

        data.foreach {
          case row =>
            assert(row.getString("name") != null)
            assert(row.getString("password") != null)
            assert(row.getString("message") != null)
            assert(row.getString("from_handle") != null)
            assert(row.getLong("post_date") > 0)
            // println("MessageId: " + row.getUuid("message_id"))
            // println("Contents: " + row.getFormattedContents())
            val df = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss-S")
            val date = df.format(row.getLong("post_date"))
          // println(date)
        }
      case Failure(exe) =>
        throw new Exception(exe)
    }
    Await.result(messages, 5.seconds)
    Future { assert(messages.isCompleted == true) }
  }

  test("delete undelivered messages for user") {
    val deleteUndelivered: String = getDeleteDelivered()

    var undeliveredDeletes: Future[Done] = cassandraSession
      .executeWrite(deleteUndelivered, "handle", "pass")
      
      undeliveredDeletes.onComplete({
        case Success(done) =>
          assert(done == Done)
        case Failure(exe) =>
          throw new Exception(exe)
      })
   
      Await.ready(undeliveredDeletes, 3.seconds)
      assert(undeliveredDeletes.isCompleted == true)
  }

  test("delete unused user") {
    val deleteUser: String = getDeleteUser()

    var userDeletes: Future[Done] = cassandraSession
      .executeWrite(deleteUser, "handle", "pass")
      
      userDeletes.onComplete({
        case Success(done) =>
          assert(done == Done)
        case Failure(exe) =>
          throw new Exception(exe)
      })
      
      Await.ready(userDeletes, 3.seconds)
      assert(userDeletes.isCompleted == true)
  }

  def completeMessageInsert(
      future: Future[PreparedStatement],
      name: String,
      pass: String,
      mess: String,
      hand: String
  ): Unit = {
    var doInsert: Future[Done] = null
    future.onComplete {
      case Success(pstmt) =>
        assert(pstmt.isInstanceOf[PreparedStatement] == true)

        var insertMessage: BoundStatement = pstmt
          .boundStatementBuilder()
          .setString("name", name)
          .setString("pass", pass)
          .setString("message", mess)
          .setString("fromhand", hand)
          .build()

        doInsert = cassandraSession.executeWrite(insertMessage)

        doInsert.onComplete {
          case Success(result) =>
            assert(result == Done)
          case Failure(exe) =>
            throw new Exception(exe)
        }
      case Failure(exe) =>
        throw new Exception(exe)
    }
  }

  def completeUserInsert(
      future: Future[PreparedStatement],
      name: String,
      pass: String,
      ip: String
  ): Unit = {
    var doInsert: Future[Done] = null

    future.onComplete {
      case Success(pstmt) =>
        assert(pstmt.isInstanceOf[PreparedStatement] == true)

        var insertUser: BoundStatement = pstmt
          .boundStatementBuilder()
          .setString("pass", name)
          .setString("name", pass)
          .setString("ip", ip)
          .build()

        doInsert = cassandraSession.executeWrite(insertUser)

        doInsert.onComplete {
          case Success(result) =>
            assert(result == Done)
          case Failure(exe) =>
            throw new Exception(exe)
        }
      case Failure(exe) =>
        throw new Exception(exe)
    }
  }

  def insertMessages(insertMessage: String): Future[PreparedStatement] = {
    val future = cassandraSession.prepare(insertMessage)
    future
  }

  def insertUsers(insertUser: String): Future[PreparedStatement] = {
    val future = cassandraSession.prepare(insertUser)
    future
  }

  def setupKeyspace(
      keySpace: String,
      cassandraSession: CassandraSession
  ): Future[Done] = {
    val keyspace: Future[Done] = cassandraSession.executeDDL(
      getCreateKeyspace
    )

    keyspace.onComplete {
      case Success(done) =>
       //
      case Failure(exe) =>
        throw new Exception(exe)
    }
    
    keyspace
  }

  def createTables(
      keySpace: String,
      cassandraSession: CassandraSession
  ): Future[Done] = {
    var future: Future[Done] = null

    while (tables.hasNext) {
      val table = tables.next()
      future = cassandraSession.executeDDL(getCreateTable(table))
    }
    future
  }
}
