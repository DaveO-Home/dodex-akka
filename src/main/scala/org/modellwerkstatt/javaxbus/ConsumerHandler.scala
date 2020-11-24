package org.modellwerkstatt.javaxbus

trait ConsumerHandler {

  def handle(msg: Message): Unit

}
