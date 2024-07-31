package com.sandinh.paho.akka

import java.nio.ByteBuffer

object ByteArrayConverters {
  implicit class Short2Array(val v: Short) extends AnyVal {
    def toByteArray: Array[Byte] = ByteBuffer.allocate(2).putShort(v).array()
  }
  implicit class Int2Array(val v: Int) extends AnyVal {
    def toByteArray: Array[Byte] = ByteBuffer.allocate(4).putInt(v).array()
  }
  implicit class Long2Array(val v: Long) extends AnyVal {
    def toByteArray: Array[Byte] = ByteBuffer.allocate(8).putLong(v).array()
  }
  implicit class String2Array(val v: String) extends AnyVal {
    def toByteArray: Array[Byte] = v.getBytes("utf-8")
  }

  implicit class RichByteArray(val a: Array[Byte]) extends AnyVal {
    def getShort: Short = ByteBuffer.wrap(a).getShort

    def getInt: Int = ByteBuffer.wrap(a).getInt

    def getLong: Long = ByteBuffer.wrap(a).getLong

    def getString = new String(a, "utf-8")
  }
}
