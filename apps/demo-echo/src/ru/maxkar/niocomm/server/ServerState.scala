package ru.maxkar.niocomm.server

import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel


/**
 * Server state handler.
 * @param bufferFactory factory used to allocate buffers for new clients.
 */
private[server] final class ServerState(
      bufferFactory : () ⇒ ByteBuffer)
    extends State {

  override def onSelection(key : SelectionKey, now : Long) : Unit = {
    val chan = key.channel().asInstanceOf[ServerSocketChannel]
    val sel = key.selector()
    do {} while (acceptOne(chan, sel, now))
  }


  override def onPing(key : SelectionKey, now : Long) : Unit = ()


  override def close(key : SelectionKey) : Seq[ByteBuffer] = {
    key.channel().close()
    Seq.empty
  }



  /* Accepts one connection. */
  private def acceptOne(
        chan : ServerSocketChannel, sel : Selector, now : Long)
      : Boolean = {
    val sock = chan.accept()
    if (sock == null)
      return false


    sock.configureBlocking(false)

    val ctx = ConnectedState.create(now,
      bufferFactory(), bufferFactory(), bufferFactory())
    val reg = sock.register(sel, SelectionKey.OP_READ, ctx)

    return true
  }
}
