package ru.maxkar.cs.chan

import java.nio.channels._
import java.io._


/** Implementation of Handlers.acceptor. */
private[chan] class ConnectHandler (
      connHandler : (SocketChannel, SelectionKey, Long) ⇒ Unit,
      connEndTime : Long,
      exnHandler : (SelectionKey, Throwable) ⇒ Unit)
    extends SelectionHandler {

  def accept(item : SelectionKey, timestamp : Long) : Unit = ()

  def connected(item : SelectionKey, timestamp : Long) : Unit = {
    val sock = item.channel().asInstanceOf[SocketChannel]
    try {
      if (sock.finishConnect()) {
        item.interestOps(item.interestOps() & ~SelectionKey.OP_CONNECT)
        connHandler(sock, item, timestamp)
      }
    } catch {
      case t : Throwable ⇒
        exnHandler(item, t)
        return
    }
  }

  def read(item : SelectionKey, timestamp : Long) : Unit = ()
  def write(item : SelectionKey, timestamp : Long) : Unit = ()
  def ping(item : SelectionKey, timestamp : Long) : Unit = {
    if (timestamp < connEndTime)
      return
    exnHandler(item, new IOException("Connection timed out"))
  }
  def demultiplex(item : SelectionKey) : Unit =
    item.channel().close()
}

