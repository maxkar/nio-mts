package ru.maxkar.cs.chan

import java.nio.channels._

/**
 * Factory for simple handlers.
 */
object Handlers {

  /**
   * Creates an accepting selection handler.
   * @param connHandler handler for the new connection.
   * @param exnHandler handler for the exceptions received
   *   during the connection. Default is to close the server
   *   channel and do nothing more.
   */
  def acceptor(
        connHandler : (SocketChannel, Selector, Long) ⇒ Unit,
        exnHandler : (ServerSocketChannel, Throwable) ⇒ Unit = null)
      : SelectionHandler =
    new AcceptorHandler(
      connHandler,
      if (exnHandler == null) defaultAcceptExnHandler else exnHandler)


  /** Default exception handler for acceptors. */
  private def defaultAcceptExnHandler(
        chan : ServerSocketChannel,
        exn  : Throwable)
      : Unit = {
    try {
      exn.printStackTrace()
    } finally {
      try {
        chan.close()
      } catch {
        case t : Throwable ⇒ t.printStackTrace()
      }
    }
  }
}
