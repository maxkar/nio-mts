package ru.maxkar.cs.msg

import java.io._
import java.nio._
import java.nio.channels._


/**
 * Message writer specification.
 * @param C type of the context associated with this writer.
 */
final class Writer[-C] private(
    extractor : C ⇒ Writer.Context,
    keyExtractor : C ⇒ SelectionKey) {

  /**
   * Performs a write on the selection data.
   * Deregisters a key from write operation if there is
   * no pending data.
   */
  def doWrite(key: SelectionKey, now : Long, context : C) : Unit = {
    val c = extractor(context)

    if (c.outBuf == null) {
      key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE)
      return
    }

    val sock = key.channel().asInstanceOf[SocketChannel]

    sock.write(c.outBuf)

    if (c.outBuf.hasRemaining())
      return

    c.outBuf.clear()

    if (c.fillBuf == null) {
      c.freeBuf2 = c.outBuf
      c.outBuf = null
      return
    }

    c.freeBuf1 = c.outBuf
    c.outBuf = c.fillBuf
    c.fillBuf.flip()
    c.fillBuf = null

    sock.write(c.outBuf)

    if (c.outBuf.hasRemaining())
      return

    c.outBuf.clear()
    c.freeBuf2 = c.outBuf
    c.outBuf = null
  }



  /** Pings a target. */
  def ping(key : SelectionKey, now : Long, context : C) : Unit = {
    val c = extractor(context)
    val b = beginWrite(c)
    b.put(0xFE.asInstanceOf[Byte])
    endWrite(context, c)
  }


  /** Sends a ping reply to a target. */
  def pingReply(key : SelectionKey, now : Long, context : C) : Unit = {
    val c = extractor(context)
    val b = beginWrite(c)
    b.put(0xFF.asInstanceOf[Byte])
    endWrite(context, c)
  }


  /** Sends a data. */
  def send(data : Array[Byte], context : C) : Unit = {
    val len = data.length
    val lol =
      if (len <= 0x3F) 1
      else if (len <= 0x3FFF) 2
      else if (len <= 0x3FFFFF) 3
      else throw new IllegalArgumentException(
        "Payload of " + len + " bytes is too large")

    val c = extractor(context)
    val buf = beginWrite(c)

    if (buf.remaining() < len + lol) {
      throw new IOException("No more buffer to send a message")
    }

    lol match {
      case 1 ⇒
        buf.put(len.asInstanceOf[Byte])
      case 2 ⇒
        buf.put(((len >> 8) | 0x40).asInstanceOf[Byte])
        buf.put(len.asInstanceOf[Byte])
      case 3 ⇒
        buf.put(((len >> 16) | 0x80).asInstanceOf[Byte])
        buf.put(((len >> 8)).asInstanceOf[Byte])
        buf.put(len.asInstanceOf[Byte])
      case _ ⇒ throw new Error("Internal failure")
    }

    buf.put(data)
    endWrite(context, c)
  }


  /**
   * Prepares a write. Returns a write buffer if needed.
   */
  private def beginWrite(c : Writer.Context) : ByteBuffer = {
    if (c.fillBuf != null)
      return c.fillBuf

    if (c.freeBuf2 != null) {
      c.fillBuf = c.freeBuf2
      c.freeBuf2 = null
    } else {
      c.fillBuf = c.freeBuf1
      c.freeBuf1 = null
    }

    c.fillBuf
  }


  /** Ends a write operation. */
  private def endWrite(ctx : C, c : Writer.Context) : Unit = {
    if (c.outBuf != null)
      return
    c.outBuf = c.fillBuf
    c.outBuf.flip()
    c.fillBuf = null

    val sk = keyExtractor(ctx)
    val s = sk.channel().asInstanceOf[SocketChannel]
    s.write(c.outBuf)

    if (c.outBuf.hasRemaining()) {
      sk.interestOps(sk.interestOps() | SelectionKey.OP_WRITE)
      return
    }

    c.outBuf.clear()
    c.freeBuf2 = c.outBuf
    c.outBuf = null
  }


}


/**
 * Writer factory and companion.
 */
object Writer {

  /** Write context. */
  final class Context private[Writer](
    private[Writer] var outBuf : ByteBuffer,
    private[Writer] var fillBuf : ByteBuffer,
    private[Writer] var freeBuf1 : ByteBuffer,
    private[Writer] var freeBuf2 : ByteBuffer)


  /** Creates a new writer.
   * @param extractor extractor to use to extract a write context.
   * @param keyExtractor extractor to use to extract associated
   *  write key.
   */
  def writer[C](
        extractor : C ⇒ Context,
        keyExtractor : C ⇒ SelectionKey)
      : Writer[C] =
    new Writer(extractor, keyExtractor)


  /**
   * Creates a new write context.
   * @param buf1 first write buffer.
   * @param buf2 second write buffer.
   */
  def context(
        buf1 : ByteBuffer,
        buf2 : ByteBuffer) :
      Context =
    new Context(null, null, buf1, buf2)
}
