/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.typesafe.com>
 */
package sbt
package internal
package server

import java.net.{ SocketTimeoutException, Socket }
import java.util.concurrent.atomic.AtomicBoolean
import sbt.protocol._

abstract class ClientConnection(connection: Socket) {

  private val running = new AtomicBoolean(true)
  private val delimiter: Byte = '\n'.toByte

  private val out = connection.getOutputStream

  val thread = new Thread(s"sbt-clientconnection-${connection.getPort}") {
    override def run(): Unit = {
      try {
        val readBuffer = new Array[Byte](4096)
        val in = connection.getInputStream
        connection.setSoTimeout(5000)
        var buffer: Vector[Byte] = Vector.empty
        var bytesRead = 0
        while (bytesRead != -1 && running.get) {
          try {
            bytesRead = in.read(readBuffer)
            buffer = buffer ++ readBuffer.toVector.take(bytesRead)
            // handle un-framing
            val delimPos = buffer.indexOf(delimiter)
            if (delimPos > 0) {
              val chunk = buffer.take(delimPos)
              buffer = buffer.drop(delimPos + 1)

              Serialization.deserializeCommand(chunk).fold(
                errorDesc => println("Got invalid chunk from client: " + errorDesc),
                onCommand
              )
            }

          } catch {
            case _: SocketTimeoutException => // its ok
          }
        }

      } finally {
        shutdown()
      }
    }
  }
  thread.start()

  def publish(event: Array[Byte]): Unit = {
    out.write(event)
    out.write(delimiter.toInt)
    out.flush()
  }

  def onCommand(command: CommandMessage): Unit

  def shutdown(): Unit = {
    println("Shutting down client connection")
    running.set(false)
    out.close()
  }

}
