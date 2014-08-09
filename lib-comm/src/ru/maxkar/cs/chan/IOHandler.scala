package ru.maxkar.cs.chan

import java.nio.channels._
import java.net.ConnectException

/**
 * Input/ouptput handler for the multiplexor. This
 * handler is invoked on each ready and valid selection
 * key in the selector.
 * <p>If any exception happens during processing a call to
 * the regular method, onHandleException will be called
 * in this handler. Common strategy is to just close the
 * socket (channel), but more advanced implementations
 * may perform additional actions like resource cleanup
 * or advanced error recovery.
 * <p>Be very carefull with IO handling. Any exceptions will
 *  be treated as belonging to _current_ selection. If you are
 *  performing other operations (like writes to some other
 *  selectors), then that other selectors can be a cause
 *  of an error. However, you will not be able to reach
 *  their channels and selection keys if you don't provide
 *  them in the exception itself.
 * @param C type of the context, value attached to the selector.
 * @param onAccept handler for the "accept" operation on the socket.
 * @param onConnect "ready to connect" handler for channels.
 * @param onRead "ready to read" handler for channels.
 * @param onWrite "ready to write" handler for channels.
 * @param onPing "regular ping" handler for channels.
 * @param onError error handler for the channel. This error
 *  handler will be called if any errors occurs during an
 *  execution of onAccept/onConnect/onRead/onWrite.
 *  This  method will not be called for exceptions caused by
 *    onError itself. Corresponding channel will be closed
 *    instead.
 */
final class IOHandler[-C](
    val onAccept : (SelectionKey, Long, C) ⇒  Unit,
    val onConnect : (SelectionKey, Long, C) ⇒ Unit,
    val onRead : (SelectionKey, Long, C) ⇒ Unit,
    val onWrite : (SelectionKey, Long, C) ⇒ Unit,
    val onPing : (SelectionKey, Long, C) ⇒ Unit,
    val onError : (SelectionKey, Long, C, Throwable) ⇒ Unit
 )




/** Companion for IO handler class. */
final object IOHandler {

  /** Creates a "polymorphic" handler which have
   * an actual behaviour dependent on a particular item.
   * @param extractor extractor of IO map for the given
   *   context.
   * @return handler which delegates handling to extractor(c)
   *   for each item.
   */
  def polymorph[C](extractor : C ⇒ IOHandler[C]) : IOHandler[C] =
    new IOHandler(
      onAccept = (k, n, c) ⇒ extractor(c).onAccept(k, n, c),
      onConnect = (k, n, c) ⇒ extractor(c).onConnect(k, n, c),
      onRead = (k, n, c) ⇒ extractor(c).onRead(k, n, c),
      onWrite = (k, n, c) ⇒ extractor(c).onWrite(k, n, c),
      onPing = (k, n, c) ⇒ extractor(c).onPing(k, n, c),
      onError = (k, n, c, e) ⇒ extractor(c).onError(k, n, c, e)
    )



  /** Ignores the request. Usefull to create IO handler structure
   * for specific context (like accept OP for client socket,
   * and read/write ops for accept operations.
   */
  def ignore(key : SelectionKey, now : Long, x : Any) : Unit = ()



  /**
   * Creates a connection acceptor. This acceptor handles only
   * accept operation and exception. All other operations are
   * ignored.
   * @param onAccept accept handler.
   * @param onError connection error handler.
   */
  def acceptor[C](
        onAccept : (SelectionKey, Long, C) ⇒ Unit,
        onError : (SelectionKey, Long, C, Throwable) ⇒ Unit)
      : IOHandler[C] =
    new IOHandler(
      onAccept = onAccept,
      onConnect = ignore,
      onRead = ignore,
      onWrite = ignore,
      onPing = ignore,
      onError = onError)



  /**
   * Creates a handler which automatically accepts
   * connections. This handlers calls an <code>afterConnect</code>
   * handler after connection is successful. Handler is not
   * called for false-positive selections (i.e. socket channel
   * will never be null).
   * @param afterConnect callback for a new connection.
   * @param onError error handler.
   */
  def autoAcceptor[C](
        afterAccept : (SocketChannel, Selector, Long) ⇒ Unit,
        onError : (SelectionKey, Long, C, Throwable) ⇒ Unit)
      : IOHandler[C] =
    acceptor(
      onAccept = (k, n, c) ⇒ {
        val newChan = k.channel().asInstanceOf[ServerSocketChannel].accept()
        if (newChan != null)
          afterAccept(newChan, k.selector(), n)
      },
      onError = onError)



  /**
   * Creates a connect hanldler. This handler handles only
   * connect operation, ping requests and exceptions.
   * All other operations are ignored.
   * @param onConnect connect handler.
   * @param onPing ping handler.
   * @param onError error handler.
   */
  def connector[C](
        onConnect : (SelectionKey, Long, C) ⇒ Unit,
        onPing : (SelectionKey, Long, C) ⇒ Unit,
        onError : (SelectionKey, Long, C, Throwable) ⇒ Unit)
      : IOHandler[C] =
    new IOHandler(
      onAccept = ignore,
      onConnect = onConnect,
      onRead = ignore,
      onWrite = ignore,
      onPing = onPing,
      onError = onError)



  /**
   * Creates an automatic connector. This connector finishes
   * connect and calls an <code>afterConnect</code> callback.
   * @param afterConnect handler to call after connection is
   *   established.
   * @param onError error handler.
   * @param connectDeadline lates time which is acceptable for
   *   the connection. Ping handler will throw ConnectException
   *   on each ping attepmt after this deadline.
   */
  def autoConnector[C](
        afterConnect : (SelectionKey, SocketChannel, Long, C) ⇒ Unit,
        onError : (SelectionKey, Long, C, Throwable) ⇒ Unit,
        connectDeadline : Long) :
      IOHandler[C] = {
    var connected = false

    connector(
      onConnect = (k, n, c) ⇒ {
        val channel = k.channel().asInstanceOf[SocketChannel]
        if (channel.finishConnect()) {
          connected = true
          afterConnect(k, channel, n, c)
        }
      },
      onPing = (k, n, c) ⇒
        if (n > connectDeadline)
          throw new ConnectException("Connection timed out"),
      onError = onError)
  }


  /**
   * Creates a communication handler. This handler handles
   * reads, writes, ping requests and errors.
   * @param onRead read request handler.
   * @param onWrite write request handler.
   * @param onPing ping request handler.
   * @param onError error handler.
   */
  def communicationHandler[C](
        onRead : (SelectionKey, Long, C) ⇒ Unit,
        onWrite : (SelectionKey, Long, C) ⇒ Unit,
        onPing : (SelectionKey, Long, C) ⇒ Unit,
        onError : (SelectionKey, Long, C, Throwable) ⇒ Unit)
      : IOHandler[C] =
    new IOHandler(
      onAccept = ignore,
      onConnect = ignore,
      onRead = onRead,
      onWrite = onWrite,
      onPing = onPing,
      onError = onError)
}
