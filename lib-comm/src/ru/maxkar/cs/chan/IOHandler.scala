package ru.maxkar.cs.chan

import java.nio.channels._

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
