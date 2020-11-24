package org.modellwerkstatt.javaxbus

trait ErrorHandler {

  def handleMsgFromBus(stillConected: Boolean,
                       readerRunning: Boolean,
                       payload: Message): Unit

  def handleException(stillConected: Boolean,
                      readerRunning: Boolean,
                      e: Exception): Unit

}
