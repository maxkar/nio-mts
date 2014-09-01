package ru.maxkar.niocomm.chat.server

import java.io.DataOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey

import scala.collection.mutable.Queue

import ru.maxkar.niocomm.MessageIO
import ru.maxkar.niocomm.Source


/**
 * State for the chatting client. This client is
 * part of the chat group, it can send and receive messages.
 * @param context messaging context.
 * @param userId user name (nick, id, etc...).
 * @param nextPing next ping timestamp.
 */
private final class ChattingState(
      context : MessageIO,
      val userId : String,
      private var nextPingRequest : Long)
    extends State {


  /** Next ping response. */
  private var nextPingResponse : Long = 0



  /** Message queue. */
  private val messageQueue = new Queue[(String, String)]



  override val incomingMessages = Source[(String, String)](
    () ⇒ !messageQueue.isEmpty, messageQueue.dequeue)



  override val outgoingMessages = context.outData



  override def onSelection(key : SelectionKey, now : Long) : State = {
    context.updateFrom(key)

    while (context.inData.hasNext) {
      val data = context.inData.next

      data.readByte() match {
        case 0 ⇒ context.outBytes += ChattingState.pingResponse
        case 1 ⇒
          nextPingRequest = now + ChattingState.pingDelay
          nextPingResponse = 0
        case 2 ⇒
          val message = data.readUTF()
          messageQueue += ((userId, message))
      }
    }

    context.writeTo(key)

    if (!context.readComplete())
      return this

    key.interestOps(key.interestOps() & ~SelectionKey.OP_READ)
    context.shutdownOutput()
    context.writeTo(key)
    return ShuttingDownState(context, now)
  }



  override def onPing(key : SelectionKey, now : Long) : Unit = {
    if (nextPingResponse > 0) {
      if (nextPingResponse < 0)
        throw new IOException("Ping expired")
      return
    }

    if (nextPingRequest > now)
      return

    nextPingResponse = now + ChattingState.pingWaitTime
    context.outBytes += ChattingState.pingRequest
    context.writeTo(key)
  }



  override def close() : Seq[ByteBuffer] = context.close()
}



/**
 * Chat state companion.
 */
private[server] final object ChattingState {
  /** Ping request message. */
  private val pingRequest = Array[Byte](0)

  /** Ping response message. */
  private val pingResponse = Array[Byte](1)

  /** Delay between pings. */
  private val pingDelay = 30000

  /** Ping wait time. */
  private val pingWaitTime = 30000



  /** Creates a new chatting state. */
  def apply(context : MessageIO, user : String, now : Long) : State =
    new ChattingState(context, user, now + pingDelay)
}
