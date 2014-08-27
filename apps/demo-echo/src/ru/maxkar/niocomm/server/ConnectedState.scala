package ru.maxkar.niocomm.server


import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.ReadableByteChannel

import ru.maxkar.niocomm._



/**
 * State for the connected client.
 * @param ioContext input/output context.
 * @param nextPingRequest timestamp for next ping request.
 */
private[server] class ConnectedState private(
      ioContext : MessageIO,
      private var nextPingRequest : Long
    ) extends State {
  import ConnectedState._

  /** Next expected ping response. */
  private var nextPingResponse : Long = 0



  override def onSelection(key : SelectionKey, now : Long) : Unit = {
    if (key.isReadable()) {
      ioContext.updateFrom(key)
      processMessages(key, now)
    }

    if (key.isValid() && key.isWritable())
      ioContext.writeTo(key)
  }



  override def onPing(key : SelectionKey, now : Long) : Unit = {
    if (nextPingResponse > 0) {
      if (nextPingResponse < now)
        throw new IOException("Ping expired")
      return
    }

    if (nextPingRequest > now) {
      ioContext.outBytes += Array[Byte](0)
      ioContext.writeTo(key)
      nextPingResponse = now + pingResponseDelay
    }
  }



  override def close(key : SelectionKey) : Seq[ByteBuffer] = {
    key.channel().close()
    ioContext.close()
  }



  /** Processes received messages. */
  private def processMessages(key : SelectionKey, now : Long) : Unit = {
    if (ioContext.readComplete) {
      key.channel.close()
      return
    }

    while (ioContext.inBytes.hasNext) {
      val msg = ioContext.inBytes.next

      if (msg.length == 0)
        throw new IOException("Empty message detected")

      msg(0) match {
        case 1 ⇒
          nextPingResponse = 0
          nextPingRequest = now + pingRequestDelay
        case 0 ⇒
          ioContext.outBytes += Array[Byte](1)
        case 2 ⇒
          ioContext.outBytes += msg
      }
    }
  }
}




/**
 * State definition for the connected client.
 */
private[server] final object ConnectedState {


  /** Delay before sending a next ping request. */
  val pingRequestDelay = 10000


  /** Delay before receiving a ping reply. */
  val pingResponseDelay = 1000


  /**
   * Creates a new state.
   */
  def create(
        now : Long, buf1 : ByteBuffer,
        buf2 : ByteBuffer, buf3 : ByteBuffer) : ConnectedState =
    new ConnectedState(
      MessageIO.create(buf1, 10000, buf2, buf3),
      now + pingRequestDelay
    )
}
