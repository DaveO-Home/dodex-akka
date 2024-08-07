/*
   Actor to manipulate cassandra - CRUD
 */

package org.dodex.db

import akka.Done
import akka.actor.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.{AbstractBehavior, ActorContext, Behaviors}
import scribe.Logger

import java.time.Instant
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future, Promise}
import scala.jdk.CollectionConverters.*
import scala.util.{Failure, Success}

import akka.stream.alpakka.cassandra.scaladsl.CassandraSession
import akka.stream.scaladsl.Sink
import com.datastax.oss.driver.api.core.cql.{BoundStatement, PreparedStatement, Row}
import mjson.Json
import org.dodex.{Capsule, ReturnData}

object DodexCassandraDML {
  import mjson.Json.*

  var log: Logger = Logger("DodexCassandra")
  val keyspace: String = "dodex"
  
  def apply(): Behavior[Capsule] =
    Behaviors.setup[Capsule](context => {
      implicit val ec: scala.concurrent.ExecutionContext =
        scala.concurrent.ExecutionContext.global
      val dodexCassandraDML = new DodexCassandraDML(context)

      Behaviors.receiveMessage { message =>
        message match {
          case DodexDml(sender, json, cassandraSession) =>
            if (json == null || json.at("msg") == null) {
              // Send noop
              log.info("noop")
            } else {
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
                case "getlogin" =>
                  val login: Future[Json] =
                    dodexCassandraDML.getLogin(
                      vertxJson,
                      cassandraSession
                    )
                  login.onComplete {
                    case Success(json) =>
                      json
                        .set("cmd", vertxJson.at("cmd").asString())
                      sender ! new ReturnData(json)
                    case Failure(exe) =>
                      throw new Exception(exe)
                  }
                case "addlogin" =>
                  val login: Future[Json] =
                    dodexCassandraDML.addLogin(
                      vertxJson,
                      cassandraSession
                    )
                  login.onComplete {
                    case Success(json) =>
                      json
                        .set("cmd", vertxJson.at("cmd").asString())
                      sender ! new ReturnData(json)
                    case Failure(exe) =>
                      throw new Exception(exe)
                  }
                case "removelogin" =>
                  val login: Future[Json] =
                    dodexCassandraDML.removeLogin(
                      vertxJson,
                      cassandraSession
                    )
                  login.onComplete {
                    case Success(json) =>
                      json
                        .set("cmd", vertxJson.at("cmd").asString())
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
  import scribe.Logger
  implicit val ec: scala.concurrent.ExecutionContext =
    scala.concurrent.ExecutionContext.global
  // val materializer =  akka.stream.Materializer.matFromSystem(
  //     /* missing */summon[akka.actor.ClassicActorSystemProvider])
  val system: akka.actor.ActorSystem = akka.actor.ActorSystem()
//  val log: LoggingAdapter = system.classicSystem.log
//  val log = Logging(system, system.classicSystem.log)
//  val log: LoggingAdapter = Logging(system.eventStream, "DodexCassandraDML")
  val log: Logger = Logger("DodexCassandraDML")

  def selectUser(
      json: Json,
      cassandraSession: CassandraSession
  ): Future[Json] = {
    val findPromise: Promise[Json] = Promise[Json]()
    val findFuture: Future[Json] = getUser(json, cassandraSession)

    findFuture.onComplete {
      case Success(findJson) =>
        if (findJson.at("msg") == null) {
          val insertUser: String = getUserInsert()
          val future: Future[PreparedStatement] =
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

  private def getUser(
      json: mjson.Json,
      cassandraSession: CassandraSession
  ): Future[mjson.Json] = {
    val jsonPayLoad: mjson.Json = Json.`object`;
    val selectUser: String = getSelectUser()
    val userPromise: Promise[Json] = Promise[Json]()
    implicit val system: ActorSystem = akka.actor.ActorSystem()
    //    var users: Future[Seq[Row]] = CassandraSource(getSelectUser(),
    //      json.at("name").asString(), json.at("password").asString())
    //      .runWith(Sink.seq)
    val users: Future[Row] = cassandraSession
      .select(
        selectUser,
        json.at("name").asString(),
        json.at("password").asString()
      )
      .map(row => row)
      .runWith(Sink.head[Row])

    users.onComplete {
      case Success(data) =>
        val userJson = Json.`object`
            userJson.set("name", data.getString("name"))
            userJson.set("password", data.getString("password"))
            userJson.set("user_id", data.getUuid("user_id").toString)
            userJson.set("ip", data.getString("ip"))
            userJson.set("last_login", data.getLong("last_login"))

            jsonPayLoad.set("msg", userJson.getValue)
        userPromise completeWith Future { jsonPayLoad }
      case Failure(exe) =>
//        throw new Exception(exe)
        userPromise completeWith Future {
          jsonPayLoad
        }
    }
    userPromise.future
  }

  def getUserByName(
      user: String,
      cassandraSession: CassandraSession
  ): Future[Row] = {
    implicit val materializer: ExecutionContextExecutor = context.executionContext
    implicit val system: ActorSystem = akka.actor.ActorSystem().classicSystem
    val selectUserByName: String = getSelectUserByName()
    val row: Future[Row] = cassandraSession
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
    val futureDelete: Future[Done] = cassandraSession
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

  private def updateUsers(
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
        try {
          updatePromise completeWith getUser(json, cassandraSession)
        } catch {
             case exception: Exception =>
               log.error("Trying to complete Promise(May need to timeout, execute again): " + exception.getMessage)
             case default@_ =>
               log.warn(
                 "Default: {} : {}" + default.getClass.getSimpleName,
                 default
               )
           }
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
    val insertPromise: Promise[Json] = Promise[Json]()

    future.onComplete {
      case Success(pstmt) =>
        val insertUser: BoundStatement = pstmt
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
    implicit val system: ActorSystem = akka.actor.ActorSystem()
    val usersPromise: Promise[Json] = Promise[Json]()
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
          row =>
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
    val insertPromise: Promise[Json] = Promise[Json]()
    val users: List[Object] = json.at("users").asList().asScala.toList

    users.foreach {
      user =>
        val findFuture: Future[Row] =
          getUserByName(user.asInstanceOf[String], cassandraSession)

        findFuture.onComplete {
          case Success(row) =>
            if (row.getString("password") != null) {
              val insertMessage: String = getMessageInsert()
              val future: Future[PreparedStatement] =
                cassandraSession.prepare(insertMessage)

              future.onComplete {
                case Success(pstmt) =>
                  val insertMessage: BoundStatement = pstmt
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
    implicit val system: ActorSystem = akka.actor.ActorSystem()
    val jsonPayLoad: mjson.Json = Json.`object`;
    val undeliveredMess: String = getSelectUndelivered()
    val userPromise: Promise[Json] = Promise[Json]()
    val users: Future[Seq[Row]] = cassandraSession
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
          row =>
            val userJson = Json.`object`
            userJson.set("name", row.getString("name"))
            userJson.set("password", row.getString("password"))
            userJson.set("messageid", row.getUuid("message_id").toString)
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
    val futureDelete: Future[Done] = cassandraSession
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

  def getLogin(
      json: mjson.Json,
      cassandraSession: CassandraSession
  ): Future[mjson.Json] = {
    implicit val system: ActorSystem = akka.actor.ActorSystem()
    val jsonPayLoad: mjson.Json = Json.`object`;
    val getLogin: String = getDodexLogin()
    val loginPromise: Promise[Json] = Promise[Json]()
    val login: Future[Row] = cassandraSession
      .select(
        getLogin,
        json.at("name").asString(),
        json.at("password").asString()
      )
      .runWith(Sink.head)

    login.onComplete {
      case Success(row) =>
        val loginJson = Json.`object`()

        loginJson.set("name", row.getString("name"))
        loginJson.set("password", row.getString("password"))
        loginJson.set("login_id", row.getUuid("login_id").toString)
        loginJson.set("last_login", row.getLong("last_login"))
        loginJson.set("status", "0")
        jsonPayLoad.set("msg", loginJson.getValue)

        loginPromise completeWith Future { jsonPayLoad }

      case Failure(exe) =>
        val loginJson = Json.`object`()

        loginJson.set("name", json.at("name"))
        loginJson.set("password", json.at("password"))
        loginJson.set("login_id", "0")
        loginJson.set("last_login", -1L)
        loginJson.set("status", "-1")
        jsonPayLoad.set("msg", loginJson.getValue)

        loginPromise completeWith Future { jsonPayLoad }
    }
    loginPromise.future
  }

  def addLogin(
      json: mjson.Json,
      cassandraSession: CassandraSession
  ): Future[mjson.Json] = {
    val insertPromise: Promise[Json] = Promise[mjson.Json]()
    val insertLogin: String = getInsertLogin()

    val future: Future[PreparedStatement] =
      cassandraSession.prepare(insertLogin)

    future.onComplete {
      case Success(pstmt) =>
        val insertLogin: BoundStatement = pstmt
          .boundStatementBuilder()
          .setString("name", json.at("name").asString())
          .setString("pass", json.at("password").asString())
          .build()

        val doInsert: Future[Done] =
          cassandraSession.executeWrite(insertLogin)

        doInsert.onComplete {
          case Success(result) =>
            getLogin(
              json,
              cassandraSession
            ).onComplete {
              case Success(result) =>
                result.set("status", "0")
                insertPromise completeWith Future { result }
              case Failure(exe) =>
                json.set("status", "-4")
                insertPromise completeWith Future { json }
            }
          case Failure(exe) =>
            json.set("status", "-4")
            insertPromise completeWith Future { json }
        }
      case Failure(exe) =>
        throw new Exception(exe)
    }

    insertPromise.future
  }

  def removeLogin(
      json: mjson.Json,
      cassandraSession: CassandraSession
  ): Future[mjson.Json] = {
    val deleteLogin: String = getRemoveLogin()
    val deletePromise: Promise[Json] = Promise[Json]()
    val futureDelete: Future[Done] = cassandraSession
      .executeWrite(
        deleteLogin,
        json.at("name").asString(),
        json.at("password").asString()
      )

    futureDelete.onComplete {
      case Success(Done) =>
        deletePromise completeWith Future {
          Json.`object`.set(
            "msg",
            json
              .set("deleted", 1)
              .set("status", "0")
              .set("last_login", Instant.now().getEpochSecond)
              .getValue
          )
        }
      case Failure(exe) =>
        deletePromise completeWith Future {
          Json.`object`.set(
            "msg",
            json
              .set("deleted", 0)
              .set("status", "-4")
              .set("last_login", Instant.now().getEpochSecond)
              .getValue
          )
        }
        throw new Exception(exe)
    }

    deletePromise.future
  }

  override def onMessage(msg: Capsule): Behavior[Capsule] = {
    Behaviors.unhandled
  }
}
