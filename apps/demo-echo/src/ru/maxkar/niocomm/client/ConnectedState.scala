package ru.maxkar.niocomm.client

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey

import scala.collection.mutable.Queue

import ru.maxkar.niocomm._

/**
 * Connected state for the client.
 */
private class ConnectedState(
      messages : Queue[String],
      ioContext : MessageIO,
      private var nextPingRequest : Long)
    extends State {

  import ConnectedState._

  /** Next expected ping response. */
  private var nextPingResponse : Long = 0



  override def onSelection(key : SelectionKey, now : Long) : Unit = {
    if (key.isReadable()) {
      ioContext.updateFrom(key)
      processMessages(key, now)
    }

    if (ioContext.readComplete) {
      System.out.println("Server is gone")
      key.channel().close()
      return
    }

    copyInput()

    if (key.isValid()) {
      onPing(key, now)

      ioContext.writeTo(key)
    }
  }



  private def onPing(key : SelectionKey, now : Long) : Unit = {
    if (nextPingResponse > 0) {
      if (nextPingResponse < now)
        throw new IOException("Ping expired")
      return
    }

    if (nextPingRequest < now) {
      ioContext.outBytes += Array[Byte](0)
      ioContext.writeTo(key)
      nextPingResponse = now + pingResponseDelay
    }
  }



  /** Copies input from queue into the output. */
  private def copyInput() : Unit =
    messages synchronized {
      while (!messages.isEmpty)
        ioContext.outBytes += 2.asInstanceOf[Byte] +: messages.dequeue.getBytes("UTF-8")
    }



  /** Processes received messages. */
  private def processMessages(key : SelectionKey, now : Long) : Unit = {
    while (ioContext.inBytes.hasNext) {
      val msg = ioContext.inBytes.next

      if (msg.length == 0)
        throw new IOException("Empty message detected")

      msg(0) match {
        case 1 ⇒
          nextPingResponse = 0
          nextPingRequest = now + pingRequestDelay
        case 0 ⇒
          System.out.println("Incoming ping!")
          ioContext.outBytes += Array[Byte](1)
        case 2 ⇒
          System.out.println(new String(msg.slice(1, msg.length), "UTF-8"))
      }
    }
  }
}



/**
 * State definition for the connected client.
 */
private[client] final object ConnectedState {


  /** Delay before sending a next ping request. */
  val pingRequestDelay = 10000


  /** Delay before receiving a ping reply. */
  val pingResponseDelay = 1000


  /**
   * Creates a new state.
   */
  def create(
        messages : Queue[String],
        now : Long) : State =
    new ConnectedState(
      messages,
      MessageIO.create(
        ByteBuffer.allocateDirect(8192),
        4000,
        ByteBuffer.allocateDirect(8192),
        ByteBuffer.allocateDirect(8192)),
      now + pingRequestDelay)
}
