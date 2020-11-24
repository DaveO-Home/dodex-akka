package org.modellwerkstatt.javaxbus

import mjson.Json

import java.io.EOFException

import java.io.IOException

import java.net.InetSocketAddress

import java.net.SocketAddress

import java.nio.ByteBuffer

import java.nio.channels.SelectionKey

import java.nio.channels.Selector

import java.nio.channels.SocketChannel

import java.nio.charset.Charset

import NonBlockingIO._

object NonBlockingIO {

  val DEFAULT_READ_BUFFER_SIZE: Int = 16000

}

class NonBlockingIO extends IOSocketService {

  private var address: SocketAddress = _

  private var socketChannel: SocketChannel = _

  private var selector: Selector = _

  private var selectionKey: SelectionKey = _

  private var utf8Charset: Charset = Charset.forName("UTF-8")

  private var readBuffer: ByteBuffer = _

  override def init(hostname: String, port: Int): Unit = {
    readBuffer = ByteBuffer.allocate(DEFAULT_READ_BUFFER_SIZE)
    address = new InetSocketAddress(hostname, port)
    socketChannel = SocketChannel.open(address)
    // not necessary, write takes 0ms
    try {
      socketChannel.configureBlocking(false)
    } catch {
      case _: IOException =>
        println("Had an IOException trying to read that file")
      case _: Exception => println("Exception")
    }

    selector = Selector.open()
    selectionKey = socketChannel.register(selector, SelectionKey.OP_READ)
  }

  override def writeToStream(msg: Json): Unit = {
    val jsonPayLoad: String = msg.toString
    val asBytes: Array[Byte] = jsonPayLoad.getBytes(utf8Charset)
    val buffer: ByteBuffer = ByteBuffer.allocate(asBytes.length + 4)
    buffer.putInt(asBytes.length)
    buffer.put(asBytes)
    buffer.flip()

    if (socketChannel.write(buffer) < 0)
      throw new Exception("Write to Stream failed")
  }

  override def readFormStream(): Json = {
    // block n read ...
    val channelsRead: Int = selector.select()
    readBuffer.clear()
    readBuffer.limit(4)
    while (readBuffer.hasRemaining())
      if (socketChannel.read(readBuffer) == -1)
        throw new EOFException("Socket closed, read returned -1")
    readBuffer.rewind()
    val length: Int = readBuffer.getInt
    readBuffer.clear()
    readBuffer.limit(length)
    while (readBuffer.hasRemaining())
      if (socketChannel.read(readBuffer) == -1)
        throw new EOFException("Socket closed, read returned -1")
    readBuffer.flip()
    val bytesForString: Array[Byte] = Array.ofDim[Byte](length)
    readBuffer.get(bytesForString, 0, length)
    val jsonMsg: String = new String(bytesForString, "UTF-8")
    System.err.println("<-- Message from Vertx: " + jsonMsg);
    Json.read(jsonMsg)
  }

  override def close(): Unit = {
    selector.close()
    socketChannel.close()
  }

}
