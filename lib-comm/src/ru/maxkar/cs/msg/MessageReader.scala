package ru.maxkar.cs.msg

import java.io.IOException
import java.io.EOFException
import java.nio._
import java.nio.channels._

/**
 * Message reader for the messaging protocol.
 * As all chan handlers, this class is single-threaded.
 * @param buffer work buffer used to store input temporary.
 * @param callback callback to invoke on received message.
 *    Will be called with null upon an eof.
 * @param pingCallback callback to invoke on ping requests.
 * @param pingResponceCallback callback to invoke when ping request
 *    is received.
 * @param messageLimit maximal message length, larger messages
 *   will cause an IO exception.
 */
final class MessageReader(
      buffer : ByteBuffer,
      callback : Array[Byte] ⇒ Unit,
      pingCallback : (Long) ⇒ Unit,
      pingResponseCallback : (Long) ⇒ Unit,
      messageLimit : Int) {

  /**
   * Buffer to fill.
   */
  private var nextBuffer : Array[Byte] = null


  /**
   * Position in "next buffer" ready to write.
   */
  private var writePos : Int = 0


  /**
   * Length reading head.
   */
  private var lengthHead : Int = 0


  /**
   * Number of bytes to read until length is complete.
   * Zero after lenght is read and next input buffer is initialized.
   */
  private var lengthPendingBytes : Int = 0



  /**
   * Performs a read from the stream.
   * Invokes a handler for fully processed messages.
   * If EOF is reached, deregisters seletor from reading.
   */
  def doRead(item : SelectionKey, now : Long) : Unit = {
    val sockChannel = item.channel().asInstanceOf[SocketChannel]
    val readStatus = sockChannel.read(buffer)

    /* Dummy read, skip. */
    if (readStatus == 0)
      return

    /* EOF condition. */
    if (readStatus < 0) {
      if (nextBuffer != null || lengthPendingBytes != 0)
        throw new EOFException("Unexpected EOF in data")

      /* Do not want to read any longer. */
      item.interestOps(item.interestOps() & ~SelectionKey.OP_READ)
      callback(null)
      return
    }

    buffer.flip()

    do {} while (processMessage(now))
    buffer.clear()
  }


  /** Tries to process an incoming message.
   * @returns true iff message was processed fully.
   */
  private def processMessage(now : Long) : Boolean = {
    if (!prepareBuffer())
      return false

    /* Special handling, protocode. */
    if (nextBuffer == null) {
      lengthHead match {
        case 0xFE ⇒
          pingCallback(now)
          return true
        case 0xFF ⇒
          pingResponseCallback(now)
          return true
        case x ⇒
          throw new IOException("Broken code, bad special " +
            Integer.toHexString(x))
      }
    }

    /* Regular read. */
    val readLimit = Math.min(buffer.remaining(), nextBuffer.length - writePos)

    if (lengthHead > 0) {
      buffer.get(nextBuffer, writePos, readLimit)
      writePos += readLimit
    }


    /* Message is not fully read. */
    if (writePos < nextBuffer.length)
      return false

    val data = nextBuffer
    nextBuffer = null
    callback(data)

    true
  }


  /** Tries to prepare an input array. */
  private def prepareBuffer() : Boolean = {
    if (nextBuffer != null)
      return true

    if (!buffer.hasRemaining())
      return false

    /* Read a length leading byte if not ready. */
    if (lengthPendingBytes == 0) {
      lengthHead = 0xFF & buffer.get()

      if (lengthHead == 0xFF || lengthHead == 0xFE)
        return true

      lengthPendingBytes = lengthHead >> 6
      if (lengthPendingBytes > 2)
        throw new IOException("Bad length marker " +
          Integer.toHexString(lengthHead))
      /* Chomp length code. */
      lengthHead = lengthHead & 0x3F
    }

    while (lengthPendingBytes > 0 && buffer.hasRemaining) {
      lengthHead = (lengthHead << 8) | (0xFF & buffer.get())
      lengthPendingBytes -= 1
    }

    if (lengthPendingBytes > 0)
      return false


    if (lengthHead > messageLimit)
      throw new IOException("Message length of " + lengthHead +
        " is greater than limit " + messageLimit)

    nextBuffer = new Array[Byte](lengthHead)
    writePos = 0

    true
  }
}
