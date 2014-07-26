package ru.maxkar.cs.chan

import java.nio.channels._


/** Implementation of Handlers.acceptor. */
private[chan] class AcceptorHandler(
      connHandler : (SocketChannel, Selector, Long) ⇒ Unit,
      exnHandler : (ServerSocketChannel, Throwable) ⇒ Unit)
    extends SelectionHandler {

  def accept(item : SelectionKey, timestamp : Long) : Unit = {
    val sock = item.channel().asInstanceOf[ServerSocketChannel]
    val chan =
      try {
        sock.accept()
      } catch {
        case t : Throwable ⇒
          exnHandler(sock.asInstanceOf[ServerSocketChannel], t)
          return
      }

    connHandler(chan, item.selector(), timestamp)
  }

  def connected(item : SelectionKey, timestamp : Long) : Unit = ()
  def read(item : SelectionKey, timestamp : Long) : Unit = ()
  def write(item : SelectionKey, timestamp : Long) : Unit = ()
  def ping(item : SelectionKey, timestamp : Long) : Unit = ()
  def demultiplex(item : SelectionKey) : Unit =
    item.channel().close()
}
