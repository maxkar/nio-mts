package ru.maxkar.cs.msg

import java.io.IOException
import java.nio._
import java.nio.channels._

/**
 * Message writer for the messaging protocol.
 * @param buffer1 first write buffer.
 * @param buffer2 second write buffer.
 * @param ownedKey selection key associated with this handler.
 */
private[msg] final class MessageWriter(
      buffer1 : ByteBuffer,
      buffer2 : ByteBuffer,
      ownedKey : SelectionKey) {

  /** Buffer to write at a current state.
   * Always in "write" mode. */
  private var writeBuf = buffer1
  writeBuf.flip()

  /** Buffer to fill while socket is not ready.
   * Always in "fill" mode. */
  private var fillBuf = buffer2


  /** Performs a write. */
  def doWrite(key : SelectionKey, now : Long) : Unit = {
    /* No data to write. */
    if (writeFully(key))
      key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE)
  }


  /** Sends a ping request. */
  def ping() : Unit =
    doWriteOp(buffer ⇒ buffer.put(0.asInstanceOf[Byte]))


  /** Sends a ping reply. */
  def pingReply(time : Long) : Unit =
    doWriteOp(buffer ⇒ buffer.put(0xFF.asInstanceOf[Byte]))

  /** Sends bytes to the receiver. */
  def send(content : Array[Byte]) : Unit = {
    val len = content.length
    if (len == 0)
      throw new IllegalArgumentException("Content can't be empty.")
    val lol =
      if (len <= 0x3F) 1
      else if (len <= 0x3FFF) 2
      else if (len <= 0x3FFFFF) 3
      else throw new IllegalArgumentException(
        "Payload of " + len + " bytes is too large")

    doWriteOp(buf ⇒ {
      if (buf.remaining() < len + lol)
        throw new IOException("No more buffer to send a message")
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

      buf.put(content)
    })
  }


  /* Performs a write operation on current buffer. */
  private def doWriteOp(handler : ByteBuffer ⇒ Unit) : Unit = {
    if (writeBuf.hasRemaining()) {
      handler(fillBuf)
      return
    }

    writeBuf.clear()
    handler(writeBuf)
    writeBuf.flip()

    val chan = ownedKey.channel().asInstanceOf[SocketChannel]
    chan.write(writeBuf)

    if (writeBuf.hasRemaining()) {
      ownedKey.interestOps(ownedKey.interestOps() | SelectionKey.OP_WRITE)
      ownedKey.selector().wakeup()
    }
  }


  /** Tries to write buffer fully. Returns true if there are no
   * pending data. Returns false if there are some pending data.
   */
  private def writeFully(key : SelectionKey) : Boolean = {
    if (!writeBuf.hasRemaining())
      return true

    val chan = key.channel().asInstanceOf[SocketChannel]
    chan.write(writeBuf)

    if (writeBuf.hasRemaining())
      return false

    var tmp = fillBuf
    fillBuf = writeBuf
    writeBuf = tmp
    fillBuf.clear()
    writeBuf.flip()

    if (!writeBuf.hasRemaining())
      return true

    chan.write(writeBuf)

    !writeBuf.hasRemaining()
  }
}
