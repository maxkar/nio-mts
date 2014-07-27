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


  /**
   * Creates a connect handler.
   * @param connExpire connection expiration time. IOException is passed
   *   to a handler if channel can't be connected till required time.
   * @param connHandler handler to invoke after the connection.
   * @param exnHandler handler for the exception received
   *  during the connect operation. Default handler closes the
   *  socket and do nothing more.
   */
  def connector(
        connExpire : Long,
        connHandler : (SocketChannel, SelectionKey, Long) ⇒ Unit,
        exnHandler : (SelectionKey, Throwable) ⇒ Unit = null)
      : SelectionHandler =
    new ConnectHandler(
      connHandler,
      connExpire,
      if (exnHandler == null) defaultConnectExnHandler else exnHandler)


  /**
   * Default exception handler for connect op.
   */
  private def defaultConnectExnHandler(
        key : SelectionKey,
        exn : Throwable)
      : Unit = {
    try {
      exn.printStackTrace()
    } finally {
      try {
        key.channel().close()
      } catch {
        case t : Throwable ⇒ t.printStackTrace()
      }
    }
  }


  /** Default exception handler for acceptors. */
  private def defaultAcceptExnHandler(
        chan : SelectableChannel,
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
