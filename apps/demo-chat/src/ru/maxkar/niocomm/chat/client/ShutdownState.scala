package ru.maxkar.niocomm.chat.client

import java.io.IOException
import java.nio.channels.SelectionKey

import ru.maxkar.niocomm.MessageIO

/**
 * Graceful shutdown state. Waits for EOF from the server.
 */
private class ShutdownState(context : MessageIO, deadTime : Long)
    extends State {

  override def handleIO(key : SelectionKey, now : Long) : Unit = {
    context.updateFrom(key)
    context.writeTo(key)

    /* Discard bytes. */
    while (context.inBytes.hasNext)
      context.inBytes.next()

    /** Check if all data are written and server closed
     * incoming connection.
     */
    if (context.ioComplete)
      key.channel().close()
    else if (deadTime < now)
      throw new IOException("Graceful close timeout")
  }
}



/** Graceful shutdown state factory. */
private[client] final object ShutdownState {
  /** Graceful close timeout. */
  private val closeTimeout = 10000


  def apply(context : MessageIO, now : Long) : State =
    new ShutdownState(context, now + closeTimeout)
}
