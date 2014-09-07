package ru.maxkar.niocomm.chat.server

import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.Selector
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel

import ru.maxkar.niocomm.MessageIO

/**
 * Connection acceptor state.
 * @param bufferFactory byte buffer allocator.
 * @param chat target chat for this server.
 */
private[server] class AcceptState(
      bufferFactory : () ⇒ ByteBuffer,
      chat : Chat)
    extends State {

  /**
   * Updates this state after the selection event.
   * @return new handling state (if appropriate).
   */
  override def onSelection(key : SelectionKey, now : Long) : State = {
    val chan = key.channel.asInstanceOf[ServerSocketChannel]
    val selector = key.selector
    do {} while (acceptOne(chan, selector, now))
    this
  }



  /** Accepts one connection. */
  private def acceptOne(
        chan : ServerSocketChannel, selector : Selector, now : Long)
      : Boolean = {

    val client = chan.accept()
    if (client == null)
      return false

    client.configureBlocking(false)

    val ctx = MessageIO.create(bufferFactory(), 10000,
      bufferFactory(), bufferFactory())

    val clientKey = client.register(selector, SelectionKey.OP_READ,
      ConnectingState(ctx, chat, now))

    return true
  }



  /**
   * Hadnles a chat-wide ping event.
   */
  override def onPing(key : SelectionKey, now : Long) : Unit = ()



  /**
   * Closes this state.
   * @return list of buffers freed from this state.
   */
  def close() : Seq[ByteBuffer] = Seq.empty
}
