/*
   Actor to manipulate cassandra - CRUD
 */

package org.dodex.db

import scala.collection.mutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Failure
import scala.util.Success

import akka.Done
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import akka.stream.scaladsl.Sink
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.Row
import mjson.Json
import org.dodex.Capsule
import org.dodex.ReturnData
import org.dodex.db.DbQueryBuilder

object DodexCassandraDML {
  import mjson.Json._;

  val keyspace: String = "dodex"

  def apply(): Behavior[Capsule] =
    Behaviors.setup[Capsule](context => {
      implicit val ec: scala.concurrent.ExecutionContext =
        scala.concurrent.ExecutionContext.global
      val dodexCassandraDML = new DodexCassandraDML(context)

      Behaviors.receiveMessage { (message) =>
        message match {
          case DodexDml(sender, json, cassandraSession) =>
            if (json == null || json.at("msg") == null) {
              // Send noop
              println("noop")
            } else {
              // val vertxJson: Json = Json.read(json.at("msg").asString())
              val vertxJson: Json = json.at("msg")
              vertxJson.at("cmd").asString() match {
                case "selectuser" =>
                  // Return a user from either inserting or updating with new login_date
                  val user: Future[Json] =
                    dodexCassandraDML.selectUser(vertxJson, cassandraSession)
                  user.onComplete {
                    case Success(json) =>
                      json
                        .set("cmd", vertxJson.at("cmd").asString())
                        .set("ws", vertxJson.at("ws").asString())
                      sender ! new ReturnData(json)
                    case Failure(exe) =>
                      throw new Exception(exe)
                  }
                case "allusers" =>
                  val users: Future[Json] =
                    dodexCassandraDML.getAllUsers(vertxJson, cassandraSession)
                  users.onComplete {
                    case Success(json) =>
                      json
                        .set("cmd", vertxJson.at("cmd").asString())
                        .set("ws", vertxJson.at("ws").asString())
                      sender ! new ReturnData(json)
                    case Failure(exe) =>
                      throw new Exception(exe)
                  }
                case "deleteuser" =>
                  val users: Future[Json] =
                    dodexCassandraDML.deleteUser(vertxJson, cassandraSession)
                  users.onComplete {
                    case Success(json) =>
                      json
                        .set("cmd", vertxJson.at("cmd").asString())
                        .set("ws", vertxJson.at("ws").asString())
                      sender ! new ReturnData(json)
                    case Failure(exe) =>
                      throw new Exception(exe)
                  }
                case "addmessage" =>
                  val users: Future[Json] =
                    dodexCassandraDML.insertMessage(vertxJson, cassandraSession)
                  users.onComplete {
                    case Success(json) =>
                      json
                        .set("cmd", vertxJson.at("cmd").asString())
                        .set("ws", vertxJson.at("ws").asString())
                      sender ! new ReturnData(json)
                    case Failure(exe) =>
                      throw new Exception(exe)
                  }
                case "delivermess" =>
                  val deliverMess: Future[Json] =
                    dodexCassandraDML.deliverMess(vertxJson, cassandraSession)
                  deliverMess.onComplete {
                    case Success(json) =>
                      json
                        .set("cmd", vertxJson.at("cmd").asString())
                        .set("ws", vertxJson.at("ws").asString())
                      sender ! new ReturnData(json)
                    case Failure(exe) =>
                      throw new Exception(exe)
                  }
                case "deletedelivered" =>
                  val users: Future[Json] =
                    dodexCassandraDML.deleteDelivered(
                      vertxJson,
                      cassandraSession
                    )
                  users.onComplete {
                    case Success(json) =>
                      json
                        .set("cmd", vertxJson.at("cmd").asString())
                        .set("ws", vertxJson.at("ws").asString())
                      sender ! new ReturnData(json)
                    case Failure(exe) =>
                      throw new Exception(exe)
                  }
                case _ =>
                // Send noop
              }
            }
        }
        Behaviors.same
      }
    })
}

class DodexCassandraDML[Capsule](context: ActorContext[Capsule])
    extends AbstractBehavior[Capsule](context)
    with DbQueryBuilder {
  implicit val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global
  implicit val system = akka.actor.ActorSystem()
  var log = system.log

  def selectUser(
      json: Json,
      cassandraSession: CassandraSession
  ): Future[Json] = {
    var findPromise: Promise[Json] = Promise[Json]()
    val findFuture: Future[Json] = getUser(json, cassandraSession)

    findFuture.onComplete {
      case Success(findJson) =>
        if (findJson.at("msg") == null) {
          val insertUser: String = getUserInsert()
          var future: Future[PreparedStatement] =
            insertUsers(insertUser, cassandraSession)
          val futureUser: Future[Json] = completeUserInsert(
            future,
            cassandraSession,
            json
          )
          findPromise completeWith futureUser
        } else {
          val futureUser: Future[Json] = updateUsers(json, cassandraSession)
          futureUser.onComplete {
            case Success(futureUser) =>
              findPromise completeWith Future { futureUser }
            case Failure(exe) =>
              throw new Exception(exe)
          }
        }
      case Failure(exe) =>
        throw new Exception(exe)
    }

    findPromise.future
  }

  def getUser(
      json: mjson.Json,
      cassandraSession: CassandraSession
  ): Future[mjson.Json] = {
    val jsonPayLoad: mjson.Json = Json.`object`;
    val selectUser: String = getSelectUser()
    val userPromise: Promise[Json] = Promise[Json]()
    var users: Future[Seq[Row]] = cassandraSession
      .select(
        selectUser,
        json.at("name").asString(),
        json.at("password").asString()
      )
      .runWith(Sink.seq)

    users.onComplete {
      case Success(data) =>
        val userJson = Json.`object`
        data.foreach {
          case row =>
            userJson.set("name", row.getString("name"))
            userJson.set("password", row.getString("password"))
            userJson.set("user_id", row.getUuid("user_id").toString())
            userJson.set("ip", row.getString("ip"))
            userJson.set("last_login", row.getLong("last_login"))

            jsonPayLoad.set("msg", userJson.getValue())
        }
        userPromise completeWith Future { jsonPayLoad }
      case Failure(exe) =>
        throw new Exception(exe)
    }
    userPromise.future
  }

  def getUserByName(
      user: String,
      cassandraSession: CassandraSession
  ): Future[Row] = {
    val selectUserByName: String = getSelectUserByName()
    var row: Future[Row] = cassandraSession
      .select(
        selectUserByName,
        user
      )
      .runWith(Sink.head)

    row
  }

  def deleteUser(
      json: mjson.Json,
      cassandraSession: CassandraSession
  ): Future[mjson.Json] = {
    val deleteUser: String = getDeleteUser()
    val deletePromise: Promise[Json] = Promise[Json]()
    var futureDelete: Future[Done] = cassandraSession
      .executeWrite(
        deleteUser,
        json.at("name").asString(),
        json.at("password").asString()
      )

    futureDelete.onComplete {
      case Success(Done) =>
        deletePromise completeWith Future { Json.`object`.set("msg", "1") }
      case Failure(exe) =>
        throw new Exception(exe)
    }

    deletePromise.future
  }

  def updateUsers(
      json: mjson.Json,
      cassandraSession: CassandraSession
  ): Future[Json] = {
    val updateUser: String = getUpdateUser()
    val updatePromise: Promise[Json] = Promise[Json]()

    val futureUpdate: Future[Done] = cassandraSession
      .executeWrite(
        updateUser,
        json.at("name").asString(),
        json.at("password").asString()
      )

    futureUpdate.onComplete {
      case Success(Done) =>
        updatePromise completeWith getUser(json, cassandraSession)
      case Failure(exe) =>
        throw new Exception(exe)
    }

    updatePromise.future
  }

  def completeUserInsert(
      future: Future[PreparedStatement],
      cassandraSession: CassandraSession,
      json: mjson.Json
  ): Future[Json] = {
    var insertPromise: Promise[Json] = Promise[Json]()

    future.onComplete {
      case Success(pstmt) =>
        var insertUser: BoundStatement = pstmt
          .boundStatementBuilder()
          .setString("name", json.at("name").asString())
          .setString("pass", json.at("password").asString())
          .setString("ip", json.at("ip").asString())
          .build()

        val doInsert: Future[Done] = cassandraSession.executeWrite(insertUser)
        doInsert.onComplete {
          case Success(result) =>
            insertPromise completeWith getUser(json, cassandraSession)
          case Failure(exe) =>
            throw new Exception(exe)
        }
      case Failure(exe) =>
        throw new Exception(exe)
    }

    insertPromise.future
  }

  def getAllUsers(
      json: mjson.Json,
      cassandraSession: CassandraSession
  ): Future[mjson.Json] = {
    var usersPromise: Promise[Json] = Promise[Json]()
    val jsonPayLoad: mjson.Json = Json.`object`;
    val selectAllUsers: String = getSelectAllUsers()
    val user: String = json.at("name").asString()
    val users: Future[Seq[Row]] = cassandraSession
      .select(
        selectAllUsers
      )
      .runWith(Sink.seq)

    users.onComplete {
      case Success(data) =>
        val userJson = Json.`object`
        val jsonArray: Array[Json] = new Array(data.length - 1)

        var i: Int = 0
        data.foreach {
          case row =>
            val name = row.getString("name")
            if (!name.equals(user)) {
              jsonArray(i) = Json.`object`.set("name", row.getString("name"))
              i += 1
            }
        }
        jsonPayLoad.set("msg", jsonArray) //set("msg", builder.toString())
        usersPromise.completeWith(Future { jsonPayLoad })
      case Failure(exe) =>
        throw new Exception(exe)
    }

    usersPromise.future
  }

  def insertMessage(
      json: Json,
      cassandraSession: CassandraSession
  ): Future[Json] = {
    var insertPromise: Promise[Json] = Promise[Json]()
    val users: List[Object] = json.at("users").asList().asScala.toList
    
    users.foreach {
      case user =>
        val findFuture: Future[Row] =
          getUserByName(user.asInstanceOf[String], cassandraSession)

        findFuture.onComplete {
          case Success(row) =>
            if (row.getString("password") != null) {
              val insertMessage: String = getMessageInsert()
              var future: Future[PreparedStatement] =
                cassandraSession.prepare(insertMessage)

              future.onComplete {
                case Success(pstmt) =>
                  var insertMessage: BoundStatement = pstmt
                    .boundStatementBuilder()
                    .setString("name", row.getString("name"))
                    .setString("pass", row.getString("password"))
                    .setUuid("userid", row.getUuid("user_id"))
                    .setString("message", json.at("message").asString())
                    .setString("fromhand", json.at("name").asString())
                    .build()

                  val doInsert: Future[Done] =
                    cassandraSession.executeWrite(insertMessage)
                  doInsert.onComplete {
                    case Success(result) =>
                      if (!insertPromise.isCompleted)
                        insertPromise completeWith Future { json }
                    case Failure(exe) =>
                      throw new Exception(exe)
                  }
                case Failure(exe) =>
                  throw new Exception(exe)
              }
            }
          case Failure(exe) =>
            throw new Exception(exe)
        }
    }
    insertPromise.future
  }
  def insertUsers(
      insertUser: String,
      cassandraSession: CassandraSession
  ): Future[PreparedStatement] = {
    val future = cassandraSession.prepare(insertUser)
    future
  }

  def deliverMess(
      json: mjson.Json,
      cassandraSession: CassandraSession
  ): Future[mjson.Json] = {
    val jsonPayLoad: mjson.Json = Json.`object`;
    val undeliveredMess: String = getSelectUndelivered()
    val userPromise: Promise[Json] = Promise[Json]()
    var users: Future[Seq[Row]] = cassandraSession
      .select(
        undeliveredMess,
        json.at("name").asString(),
        json.at("password").asString()
      )
      .runWith(Sink.seq)

    users.onComplete {
      case Success(seq) =>
        val jsonArray: Array[Json] = new Array(seq.length)
        var occ: Int = 0

        seq.foreach {
          case row =>
            val userJson = Json.`object`
            userJson.set("name", row.getString("name"))
            userJson.set("password", row.getString("password"))
            userJson.set("messageid", row.getUuid("message_id").toString())
            userJson.set("message", row.getString("message"))
            userJson.set("postdate", row.getLong("post_date"))
            userJson.set("fromhandle", row.getString("from_handle"))
            jsonArray(occ) = userJson
            occ += 1
        }

        jsonPayLoad.set("msg", jsonArray)
        userPromise completeWith Future { jsonPayLoad }
      case Failure(exe) =>
        throw new Exception(exe)
    }
    userPromise.future
  }

  def deleteDelivered(
      json: mjson.Json,
      cassandraSession: CassandraSession
  ): Future[mjson.Json] = {
    val deleteDelivered: String = getDeleteDelivered()
    val deletePromise: Promise[Json] = Promise[Json]()
    var futureDelete: Future[Done] = cassandraSession
      .executeWrite(
        deleteDelivered,
        json.at("name").asString(),
        json.at("password").asString()
        // java.util.UUID.fromString(json.at("messageid").asString())
      )

    futureDelete.onComplete {
      case Success(Done) =>
        deletePromise completeWith Future {
          Json.`object`.set("msg", Json.`object`.set("deleted", 1))
        }
      case Failure(exe) =>
        deletePromise completeWith Future {
          Json.`object`.set("msg", Json.`object`.set("deleted", -1))
        }
        throw new Exception(exe)
    }

    deletePromise.future
  }

  override def onMessage(msg: Capsule): Behavior[Capsule] = {
    Behaviors.unhandled
  }
}
