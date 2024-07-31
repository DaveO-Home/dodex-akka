package org.dodex.db

import scala.util.control.Breaks._

import com.datastax.oss.driver.api.core.cql._
import com.datastax.oss.driver.api.querybuilder.QueryBuilder._
import com.datastax.oss.driver.api.querybuilder.SchemaBuilder._

object CreateTable extends Enumeration {
  var keyspace = "dodex"
  var keyspaceSql: Option[String] = None

  val CREATEKEYSPACE: Value = Value(
    createKeyspace(keyspace)
      .ifNotExists()
      .withSimpleStrategy(1)
      .build()
      .getQuery()
  )
  val CREATEUSERS: Value = Value(
    createTable(keyspace, "user_message")
      .ifNotExists()
      .withPartitionKey(
        "name",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TEXT
      )
      .withPartitionKey(
        "password",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TEXT
      )
      .withColumn(
        "user_id",
        com.datastax.oss.driver.api.core.`type`.DataTypes.UUID
      )
      .withColumn("ip", com.datastax.oss.driver.api.core.`type`.DataTypes.TEXT)
      .withColumn(
        "last_login",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TIMESTAMP
      )
      .withComment("Dodex registered users by name/password")
      .build()
      .getQuery()
  )
  val CREATEMESSAGES: Value = Value(
    createTable(keyspace, "message_user")
      .ifNotExists()
      .withPartitionKey(
        "name",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TEXT
      )
      .withPartitionKey(
        "password",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TEXT
      )
      .withClusteringColumn(
        "message_id",
        com.datastax.oss.driver.api.core.`type`.DataTypes.UUID
      )
      .withColumn(
        "user_id",
        com.datastax.oss.driver.api.core.`type`.DataTypes.UUID
      )
      .withColumn(
        "message",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TEXT
      )
      .withColumn(
        "from_handle",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TEXT
      )
      .withColumn(
        "post_date",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TIMESTAMP
      )
      .withComment("Dodex undelivered messages by name/password, message id")
      .build()
      .getQuery()
  )
  val CREATELOGIN: Value = Value(
    createTable(keyspace, "login")
      .ifNotExists()
      .withPartitionKey(
        "name",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TEXT
      )
      .withPartitionKey(
        "password",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TEXT
      )
      .withColumn(
        "login_id",
        com.datastax.oss.driver.api.core.`type`.DataTypes.UUID
      )
      .withColumn(
        "last_login",
        com.datastax.oss.driver.api.core.`type`.DataTypes.TIMESTAMP
      )
      .withComment("Dodex undelivered messages by name/password, message id")
      .build()
      .getQuery()
  )
}

trait DbCassandra {
  val tables: Iterator[String] =
    Iterator("user_message", "message_user", "login")

  def getCreateTable(createValue: String): String = {
    var isSql = false
    var sql: String = null

    breakable {
      CreateTable.values.filter(sqlCmd => {
        if (
          sqlCmd
            .toString
            .toLowerCase()
            .contains(CreateTable.keyspace + "." + createValue)
        ) {
          isSql = true
          sql = sqlCmd.toString
          break()
        }
        isSql
      })
    }
    sql
  }

  def getCreateKeyspace: String = {
    CreateTable.CREATEKEYSPACE.toString
  }
}
