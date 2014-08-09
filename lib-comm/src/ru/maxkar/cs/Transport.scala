package ru.maxkar.cs

import java.io._
import java.nio._
import java.nio.channels._


/**
 * Utilities for the transport level.
 */
object Transport {


  /** Reader implementation. */
  private[Transport] class Reader[C](
      extractor : C ⇒ ByteBuffer,
      onMessage : (SelectionKey, Long, ByteBuffer, C) ⇒ Unit,
      onEof : (SelectionKey, Long, C) ⇒ Unit) {


    /** Reads data into buffer. */
    private[Transport] def doRead(
          key : SelectionKey,
          now : Long,
          context : C)
        : Unit = {
      val channel = key.channel().asInstanceOf[SocketChannel]
      val buffer = extractor(context)


      do {} while (readPortion(key, now, channel, buffer, context))
    }


    /** Reads one data portion.
     * Returns true iff next portion should be read.
     */
    private def readPortion(
          key : SelectionKey,
          now : Long,
          channel : SocketChannel,
          buffer : ByteBuffer,
          context : C) : Boolean = {

       val updateLength = channel.read(buffer)

       if (updateLength == 0)
         return false

       if (updateLength < 0) {
         try {
           onEof(key, now, context)
           return false
         } finally {
           /* Reset read if channel is still open. */
           if (key.isValid())
             key.interestOps(key.interestOps & ~SelectionKey.OP_READ)
         }
       }

       buffer.flip()
       onMessage(key, now, buffer, context)
       buffer.compact()
       if (!buffer.hasRemaining())
         throw new IOException("Reading stuck on the full buffer")
       true
    }
  }


  /**
   * Creates a new reader which reads data to the buffer
   * and ivokes callback on buffer.
   * @param C type of the context.
   * @param extractor function to extract data.
   * @param onMessage message handler. This message should consume
   *  as much data from buffer as possible. This handler must not
   *  leave full buffer as full (i.e. it must read at least one byte
   *  from buffer if buffer already reached it's capacity.
   * @param onEof End-of-file handler.
   * @returns hadnler function compatible with IOHandler.
   */
  def byteBufferReader[C](
        extractor : C ⇒ ByteBuffer,
        onMessage : (SelectionKey, Long, ByteBuffer, C) ⇒ Unit,
        onEof : (SelectionKey, Long, C) ⇒ Unit)
      : (SelectionKey, Long, C) ⇒ Unit =
    new Reader(extractor, onMessage, onEof).doRead

}
