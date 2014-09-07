package ru.maxkar.niocomm.chat.client

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

import ru.maxkar.niocomm.MessageIO
import ru.maxkar.niocomm.Source

/**
 * Client communication handler state.
 */
private final class ConnectingState(
      name : String, queue : Source[String], deathTime : Long)
    extends State {

  /** Handles a communication event. */
  override def handleIO(key : SelectionKey, now : Long) : Unit = {
    val chan = key.channel().asInstanceOf[SocketChannel]

    if (!chan.finishConnect()) {
      if (deathTime < now)
        throw new IOException("Connection timed out")
      return
    }

    key.interestOps(SelectionKey.OP_READ)
    val ctx = MessageIO.create(
      ByteBuffer.allocateDirect(1024 * 1024),
      100000,
      ByteBuffer.allocateDirect(1024 * 1024),
      ByteBuffer.allocateDirect(1024 * 1024))
    val ns = HandshakeState(name, queue, ctx, now)
    key.attach(ns)
    ns.handleIO(key, now)
  }
}



/** Connected state factory. */
private[client] final object ConnectingState {
  private val connTimeout = 10000


  /** Creates a new connecting state. */
  def apply(name : String, queue : Source[String], now  : Long) : State =
    new ConnectingState(name, queue, now + connTimeout)
}
