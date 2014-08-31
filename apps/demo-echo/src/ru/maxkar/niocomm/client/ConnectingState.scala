package ru.maxkar.niocomm.client


import java.io.IOException
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

import scala.collection.mutable.Queue



/** "Connecting" handler. */
private final class ConnectingState(
      messages : Queue[String],
      deadTime : Long)
    extends State {

  override def onSelection(key : SelectionKey, now : Long) : Unit = {
    if (now > deadTime)
      throw new IOException("Connection timed out")

    val sock = key.channel().asInstanceOf[SocketChannel]
    if (!sock.finishConnect())
      return

    key.interestOps(SelectionKey.OP_READ)
    key.attach(ConnectedState.create(messages, now))
  }
}



/**
 *  Connecting state factory.
 */
final object ConnectingState {

  /** Creates a new state. */
  def create(messages : Queue[String], now : Long) : State =
    new ConnectingState(messages, now + 5000)
}
