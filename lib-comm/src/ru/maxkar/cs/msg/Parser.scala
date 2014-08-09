package ru.maxkar.cs.msg

import java.io._
import java.nio._
import java.nio.channels._


/**
 * Message parser for the transport layer.
 * @param C type of the context.
 */
final class Parser[-C] private(
    extractor : C ⇒ Parser.Context,
    onMessage : (SelectionKey, Long, Array[Byte], C) ⇒ Unit,
    onEof : (SelectionKey, Long, C) ⇒ Unit,
    onPingRequest : (SelectionKey, Long, C) ⇒ Unit,
    onPingResponse : (SelectionKey, Long, C) ⇒ Unit,
    messageSizeLimit : Int) {

  import Parser._


  /**
   * Consumes all data in the <code>data</code> buffer.
   * Updates a parsing state and invokes data or ping
   * callbacks when messages are ready.
   */
  def update(
        key : SelectionKey,
        now : Long,
        data : ByteBuffer,
        context : C)
      : Unit = {
    val c = extractor(context)

    do {} while (processMessage(key, now, data, context, c))
  }


  /** Consumes an EOF. */
  def finish(key : SelectionKey, now : Long, context : C) : Unit = {
    val c = extractor(context)
    if (c.nextMessage != null || c.remainingLengthBytes > 0)
      throw new IOException("Unexpected end of steam")
    onEof(key, now, context)
  }


  /**
   * Processes one message.
   * @returns true if message was fetched completely,
   *   false otherwise.
   */
  private def processMessage(
        key : SelectionKey,
        now : Long,
        data : ByteBuffer,
        context : C,
        c : Context)
      : Boolean = {

    if (!prepareBuffer(data, c))
      return false

    /* Command. */
    if (c.nextMessage == null) {
      c.length match {
        case 0xFF ⇒ onPingResponse(key, now, context)
        case 0xFE ⇒ onPingRequest(key, now, context)
        case x ⇒
          c.length = 0
          throw new IOException("Bad control code " + x)
      }

      return true
    }

    val readLimit = Math.min(data.remaining(), c.length - c.writePosition)

    data.get(c.nextMessage, c.writePosition, readLimit)
    c.writePosition += readLimit

    if (c.writePosition < c.length)
      return false

    val message = c.nextMessage
    c.nextMessage = null
    c.writePosition = 0


    onMessage(key, now, message, context)

    true
  }



  /** Prepares a data buffer (if buffer is not ready) in the context. */
  private def prepareBuffer(data : ByteBuffer, c : Context) : Boolean = {
    if (c.nextMessage != null)
      return true

    if (!data.hasRemaining())
      return false


    if (c.remainingLengthBytes == 0) {
      val lead = data.get() & 0xFF

      c.remainingLengthBytes = lead >> 6

      /* Command. Reset length, but do not create buffer. */
      if (c.remainingLengthBytes == 3) {
        c.remainingLengthBytes = 0
        c.length = lead
        return true
      }

      c.length = lead & 0x3F
    }


    while (c.remainingLengthBytes > 0 && data.hasRemaining()) {
      c.length = (c.length << 8) | (data.get() & 0xFF)
      c.remainingLengthBytes -= 1
    }

    if (c.remainingLengthBytes > 0)
      return false

    if (c.length > messageSizeLimit)
      throw new IOException("Message length " + c.length +
        " is outside of protocol limit " + messageSizeLimit)

    c.nextMessage = new Array[Byte](c.length)
    true
  }
}


/**
 * Message parser companion object.
 */
final object Parser {

  /** Message parsing context. */
  final class Context private[Parser]() {
    /**
     * Next message body to populate.
     * Set to null while reading the message.
     */
    private[Parser] var nextMessage : Array[Byte] = null


    /** Write position inside nextMessage. */
    private[Parser] var writePosition : Int = 0


    /** Message length. */
    private[Parser] var length : Int = 0


    /** Remaining number of bytes for the length. */
    private[Parser] var remainingLengthBytes : Int = 0
  }


  /** Creates new parsing context. */
  def context() : Context = new Context()


  /**
   * Creates a new message parser.
   * @param extractor function used to extract messaging
   *  context from the handling context.
   * @param onMessage message handler.
   * @param onEof end-of-file handler.
   * @param onPingRequest ping request handler.
   * @param onPingResponse ping response handler.
   * @param messageSizeLimit maximal message size.
   */
  def parser[C](
        extractor : C ⇒ Parser.Context,
        onMessage : (SelectionKey, Long, Array[Byte], C) ⇒ Unit,
        onEof : (SelectionKey, Long, C) ⇒ Unit,
        onPingRequest : (SelectionKey, Long, C) ⇒ Unit,
        onPingResponse : (SelectionKey, Long, C) ⇒ Unit,
        messageSizeLimit : Int = Integer.MAX_VALUE)
      : Parser[C] =
    new Parser(extractor, onMessage, onEof,
      onPingRequest, onPingResponse, messageSizeLimit)
}
