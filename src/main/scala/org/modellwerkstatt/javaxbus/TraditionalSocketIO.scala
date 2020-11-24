package org.modellwerkstatt.javaxbus

import mjson.Json

import java.io.DataInputStream

import java.io.DataOutputStream

import java.io.IOException

import java.net.Socket

import java.nio.charset.Charset

class TraditionalSocketIO extends IOSocketService {

  private var socket: Socket = _

  private var reader: DataInputStream = _

  private var writer: DataOutputStream = _

  private var utf8Charset: Charset = Charset.forName("UTF-8")

  def init(hostname: String, port: Int): Unit = {
    socket = new Socket(hostname, port)
    // from server
    reader = new DataInputStream(socket.getInputStream)
    // to server
    writer = new DataOutputStream(socket.getOutputStream)
  }

  def close(): Unit = {
    reader.close()
    writer.close()
    socket.close()
  }

  def writeToStream(msg: Json): Unit = {
    synchronized {
      val jsonPayLoad: String = msg.toString
      val asBytes: Array[Byte] = jsonPayLoad.getBytes(utf8Charset)
      // big endian
      writer.writeInt(asBytes.length)
      writer.write(asBytes)
    }
// long millis = System.currentTimeMillis();
// writer.flush();
// long result = System.currentTimeMillis() - millis;
// System.err.println("T " + result  +" ms <-- " + jsonPayLoad);
// long millis = System.currentTimeMillis();
// writer.flush();
// long result = System.currentTimeMillis() - millis;
// System.err.println("T " + result  +" ms <-- " + jsonPayLoad);
  }

  def readFormStream(): Json = {
    // read complete msg
    val len: Int = reader.readInt()
    val message: Array[Byte] = Array.ofDim[Byte](len)
    reader.readFully(message, 0, len)
    val jsonMsg: String = new String(message, "UTF-8")
    Json.read(jsonMsg)
  }
// System.err.println("<<-- " + new String(message));
// System.err.println("<<-- " + new String(message));

}

