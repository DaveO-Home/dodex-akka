package org.dodex.db

import com.datastax.oss.driver.api.querybuilder.QueryBuilder._
import com.datastax.oss.driver.api.core.cql._
import com.datastax.oss.driver.api.querybuilder.relation.Relation

object CreateDML extends Enumeration {
  var keyspace = "dodex"

  val USERINSERT = Value(
    insertInto(keyspace, "user_message")
      .value("user_id", now())
      .value("name", bindMarker("name"))
      .value("password", bindMarker("pass"))
      .value("last_login", toTimestamp(now()))
      .value("ip", bindMarker("ip"))
      .build()
      .getQuery()
  )
  val MESSAGEINSERT = Value(
    insertInto(keyspace, "message_user")
      .value("message_Id", now())
      .value("name", bindMarker("name"))
      .value("password", bindMarker("pass"))
      .value("message", bindMarker("message"))
      .value("from_handle", bindMarker("fromhand"))
      .value("post_date", toTimestamp(now()))
      .value("user_id", bindMarker("userid"))
      .build()
      .getQuery()
  )
  val SELECTUSER = Value(
    selectFrom(keyspace, "user_message")
      .column("user_Id")
      .column("name")
      .column("password")
      .column("ip")
      .raw("toUnixTimestamp(last_login) as last_login")
      .where(
        Relation.column("name").isEqualTo(bindMarker()),
        Relation.column("password").isEqualTo(bindMarker())
      )
      .build()
      .getQuery()
  )
  val SELECTUSERBYNAME = Value(
    selectFrom(keyspace, "user_message")
      .column("user_Id")
      .column("name")
      .column("password")
      .column("ip")
      .raw("toUnixTimestamp(last_login) as last_login")
      .where(
        Relation.column("name").isEqualTo(bindMarker())
      ).allowFiltering()
      .build()
      .getQuery()
  )
  val SELECTUNDELIVERED = Value(
    selectFrom(keyspace, "message_user")
      .column("message_Id")
      .column("name")
      .column("password")
      .column("message")
      .column("from_handle")
      .raw("toUnixTimestamp(post_date) as post_date")
      .where(
        Relation.column("name").isEqualTo(bindMarker()),
        Relation.column("password").isEqualTo(bindMarker())
      ).allowFiltering()
      .build()
      .getQuery()
  )
  val DELETEDELIVERED = Value(
    deleteFrom(keyspace, "message_user")
      .where(
        Relation.column("name").isEqualTo(bindMarker()),
        Relation.column("password").isEqualTo(bindMarker()),
        // Relation.column("message_id").isEqualTo(bindMarker())
      )
      .build()
      .getQuery()
  )
  val DELETEUSER = Value(
    deleteFrom(keyspace, "user_message")
      .where(
        Relation.column("name").isEqualTo(bindMarker()),
        Relation.column("password").isEqualTo(bindMarker())
      )
      .build()
      .getQuery()
  )
  val UPDATEUSER = Value(
    update(keyspace, "user_message").setColumn("last_login", toUnixTimestamp(now()))
      .where(
        Relation.column("name").isEqualTo(bindMarker()),
        Relation.column("password").isEqualTo(bindMarker())
      )
      .build()
      .getQuery()
  )
  val SELECTALLUSERS = Value(
    selectFrom(keyspace, "user_message")
      .column("name")
      .build()
      .getQuery()
  )
}

trait DbQueryBuilder {
  def getUserInsert(): String = {
    CreateDML.USERINSERT.toString()
  }

  def getMessageInsert(): String = {
    CreateDML.MESSAGEINSERT.toString()
  }

   def getSelectUser(): String = {
    CreateDML.SELECTUSER.toString()
  }

  def getSelectUndelivered(): String = {
    CreateDML.SELECTUNDELIVERED.toString()
  }

  def getDeleteDelivered(): String = {
    CreateDML.DELETEDELIVERED.toString()
  }

  def getDeleteUser(): String = {
    CreateDML.DELETEUSER.toString()
  }

  def getUpdateUser(): String = {
    CreateDML.UPDATEUSER.toString()
  }

  def getSelectAllUsers(): String = {
    CreateDML.SELECTALLUSERS.toString()
  }

  def getSelectUserByName(): String = {
    CreateDML.SELECTUSERBYNAME.toString()
  }

  def setKeyspace(keyspace: String) = {
    CreateDML.keyspace = keyspace;
  }
}
