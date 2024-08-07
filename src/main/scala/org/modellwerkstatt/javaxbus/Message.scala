package org.modellwerkstatt.javaxbus

import mjson.Json

import scala.beans.BeanProperty
import scala.compiletime.uninitialized

class Message(
    @BeanProperty var address: String,
    private var send: Boolean,
    @BeanProperty var replyAddress: String,
    private var payLoad: Json
) {

  private var typeError: Boolean = false

  private var errorMessage: String = uninitialized

  private var errorCode: String = uninitialized

  private var errorType: String = uninitialized

  def this(
      adr: String,
      sended: Boolean,
      reply: String,
      message: String,
      failCode: String,
      failType: String
  ) = {
    this(adr, sended, reply, null)
    typeError = true
    send = sended
    address = adr
    replyAddress = reply
    errorMessage = message
    errorCode = failCode
    errorType = failType
  }

  def isPublishedMsg: Boolean = !send

  def getBodyAsMJson: Json = {
    if (isErrorMsg) {
      val exMsg: String = String.format(
        "This is a error msg '%s' (code: %s, type %s), no body present!",
        getErrMessage,
        getErrFailureCode,
        getErrFailureType
      )
      throw new IllegalStateException(exMsg)
    }
    payLoad
  }

  private def isErrorMsg: Boolean = typeError

  private def getErrFailureCode: String = {
    if (!isErrorMsg) {
      throw new IllegalStateException(
        "This is not an error message! Msg body is " + getBodyAsShortString
      )
    }
    errorCode
  }

  private def getErrFailureType: String = {
    if (!isErrorMsg) {
      throw new IllegalStateException(
        "This is not an error message! Msg body is " + getBodyAsShortString
      )
    }
    errorType
  }

  private def getErrMessage: String = {
    if (!isErrorMsg) {
      throw new IllegalStateException(
        "This is not an error message! Msg body is " + getBodyAsShortString
      )
    }
    errorMessage
  }

  private def getBodyAsShortString: String = payLoad.toString(50)

  override def toString: String = {
    if (isErrorMsg) {
      String.format(
        "[ErrorMsg '%s' (code %s, type %s)]",
        getErrMessage,
        getErrFailureCode,
        getErrFailureType
      )
    }
    "[Message " + payLoad + "]"
  }

  // def getAddress(): String = address
}
