package ru.maxkar.niocomm.chat.server


import java.io.IOException
import java.nio.channels.SelectionKey
import java.nio.ByteBuffer

import ru.maxkar.niocomm.MessageIO


/**
 * State which is performing a gracefull shutdown.
 */
private final class ShuttingDownState private(
      context : MessageIO,
      flushDeadTime : Long)
    extends State {



  override def onSelection(key : SelectionKey, now : Long) : State = {
    context.writeTo(key)
    this
  }



  override def onPing(key : SelectionKey, now : Long) : Unit =
    if (flushDeadTime < now)
      throw new IOException("Graceful close timeout expired")



  override def isDone() : Boolean = context.writeComplete()



  override def close() : Seq[ByteBuffer] = context.close()
}



/**
 * State companion.
 */
private[server] final object ShuttingDownState {
  /** Tiemout to wait for data flush. */
  val flushTimeout = 10000


  /** Creates a new "graceful shutdown" state. */
  def apply(context : MessageIO, now : Long) : State =
    new ShuttingDownState(context, now + flushTimeout)
}
