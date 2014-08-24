package ru.maxkar.niocomm

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

import scala.collection.mutable.Queue

/**
 * Message formatter and writter. This writer writes messages
 * in a message-delimited format. Receiving side will be able to
 * restore message bounds in the incoming stream. Message size is
 * limited to 2^30 bytes.
 */
final object MessageWriter {

  /** Write context specification. */
  final class T private[MessageWriter] {
    /** Free write buffers. */
    private[MessageWriter] val freeBuffers = new Queue[ByteBuffer]

    /** Filled write buffers. */
    private[MessageWriter] val fullBuffers = new Queue[ByteBuffer]

    /** Current write buffer. */
    private[MessageWriter] var currentBuffer : ByteBuffer = null
  }


  /**
   * Exception denoting that there are no space to write the
   * message.
   */
  final class NoWriteSpace(
        val required : Int,
        val free : Int)
      extends IOException(
        "Insufficient buffer space, required " +
        required + " but have only " + free)



  /**
   * Creates a new context. Returned context have no synthetic
   * restrictions on queue lengths.
   */
  def unboundedContext() : T = new T()



  /**
   * Checks if there are full buffers.
   * @param context context to check.
   * @returns <code>true</code> if there is at least one buffer filled
   * up to its capacity.
   */
  def hasFullBuffer(context : T) : Boolean =
    !context.fullBuffers.isEmpty



  /**
   * Checks if there is a "ready" buffer. Buffer is considered
   * ready if it have some data in it. Full buffer is definitely
   * is "ready".
   * @param context context to check for the ready buffer.
   * @return <code>true</code> iff context has buffer with some
   * data in it.
   */
  def hasReadyBuffer(context : T) : Boolean =
    hasFullBuffer(context) ||
    (context.currentBuffer != null && context.currentBuffer.position > 0)



  /**
   * Checks if context have sufficient space for the message of
   * specified length.
   * @param context context to probe.
   * @param length desired message length.
   * @return <code>true</code> iff buffers in the context can accomodate
   * next message with target length.
   * @throws IllegalArgumentException if <code>length</code> is greater
   * than maximal allowed length.
   */
  def hasSpaceFor(context : T, length : Int) : Boolean = {
    var remainingLength = length + lengthOfLength(length)

    if (context.currentBuffer != null)
      remainingLength -= context.currentBuffer.remaining()

    val itr = context.freeBuffers.iterator
    while (remainingLength > 0 && itr.hasNext)
      remainingLength -= itr.next.capacity()

    return remainingLength <= 0

  }



  /**
   * Writes a message into the context's buffers.
   * @param context context to write a message to.
   * @param message message to write.
   * @throws IllegalArgumentException if message capacity exceeds
   * maximal allowed message size of 2^30.
   * @throws NoWriteSpace if there are insufficient buffer space
   * to accomodate the whole message.
   */
  def writeMessage(context : T, message : Array[Byte]) : Unit = {
    val len = message.length

    if (!hasSpaceFor(context, len))
      throw new NoWriteSpace(
        message.length + lengthOfLength(len),
        getFreeSpace(context))

    val lol = lengthOfLength(len)
    lol match {
      case 1 ⇒
        writeByte(context, len)
      case 2 ⇒
        writeByte(context, (len >> 8) | 0x40)
        writeByte(context, len)
      case 3 ⇒
        writeByte(context, (len >> 16) | 0x80)
        writeByte(context, len >> 8)
        writeByte(context, len)
      case 4 ⇒
        writeByte(context, (len >> 24) | 0xC0)
        writeByte(context, len >> 16)
        writeByte(context, len >> 8)
        writeByte(context, len)
    }


    var contentPtr = 0

    while (contentPtr < len) {
      prepareWriteBuffer(context)
      val toWrite = Math.min(len - contentPtr, context.currentBuffer.remaining())
      context.currentBuffer.put(message, contentPtr, toWrite)
      flushIfFull(context)
      contentPtr += toWrite
    }
  }



  /**
   * Performs a "writer" callback on the data stream and writes
   * received message to the buffer.
   * @param context context to write a message to.
   * @param writer stream callback.
   * @throws IllegalArgumentException if writer created a message
   * of capacity greater than allowed message size.
   * @throws NoWriteSpace if there is no sufficient buffer space
   * to accomodate the whole message.
   */
  def writeWithStream(context : T, callback : OutputStream ⇒ Unit) : Unit = {
    val os = new ByteArrayOutputStream()
    callback(os)
    writeMessage(context, os.toByteArray())
  }



  /**
   * Performs a "data writer" callback on the data stream and
   * writes a new message to the buffer
   * @param context context to write a message to.
   * @param writer stream callback.
   * @throws IllegalArgumentException if writer created a message
   * of capacity greater than allowed message size.
   * @throws NoWriteSpace if there is no sufficient buffer space
   * to accomodate the whole message.
   */
  def writeWithDataStream(context : T, callback : DataOutputStream ⇒ Unit) : Unit =
    writeWithStream(context, os ⇒ callback(new DataOutputStream(os)))



  /**
   * Adds a new write buffer. Buffer is cleared upon adding.
   * @param context context to add buffer to.
   * @param buffer buffer to add.
   * @throws IllegalArgumentException if buffer have zero capacity.
   */
  def addBuffer(context : T, buffer : ByteBuffer) : Unit = {
    buffer.clear()

    if (!buffer.hasRemaining())
      throw new IllegalArgumentException(
        "Buffer must have positive capacity")

    context.freeBuffers += buffer
  }



  /**
   * Returns a full buffer. Buffer is considered full
   * if it reaches it's capacity. Returned buffer is
   * "flipped" and is ready for reading.
   * @param context context to extract next full buffer.
   * @return next full buffer or <code>null</code> if there are
   * no full buffers.
   */
  def getFullBuffer(context : T) : ByteBuffer =
    if (context.fullBuffers.isEmpty)
      null
    else {
      val res = context.fullBuffers.dequeue
      res.flip()
      res
    }



  /**
   * Returns a "ready" buffer. Buffer is considered ready
   * if it have some data in it. Returned buffer is "flipped" and
   * is ready for reading.
   * @param context context to extract next ready buffe.r
   * @return next ready buffer or <code>null</code> if there are
   * no data in the context.
   */
  def getReadyBuffer(context : T) : ByteBuffer = {
    val full = getFullBuffer(context)
    if (full != null)
      return full

    if (context.currentBuffer == null || context.currentBuffer.position == 0)
      return null

    val res = context.currentBuffer
    context.currentBuffer = null
    res.flip()
    res
  }



  /**
   * Clears all the buffers from the context by dropping
   * ready, pending and empty buffers.
   * @param context context to clear.
   * @return list of all released buffers.
   */
  def clear(context : T) : Seq[ByteBuffer] = {
    val res = new scala.collection.mutable.ArrayBuffer[ByteBuffer]
    res ++= context.freeBuffers.drop(0)
    res ++= context.fullBuffers.drop(0)
    if (context.currentBuffer != null) {
      res += context.currentBuffer
      context.currentBuffer = null
    }
    res
  }




  /**
   * Writes one byte into the context.
   * Context must have sufficient space to write that byte.
   */
  private def writeByte(context : T, byte : Int) : Unit = {
    prepareWriteBuffer(context)
    context.currentBuffer.put(byte.asInstanceOf[Byte])
    flushIfFull(context)
  }



  /**
   * Prepares a buffer for write. Ensures that current buffer
   * is set to a buffer with some free space in it.
   */
  private def prepareWriteBuffer(context : T) : Unit =
    if (context.currentBuffer == null)
      context.currentBuffer = context.freeBuffers.dequeue



  /**
   * Flushes current write buffer if it is full.
   */
  private def flushIfFull(context : T) : Unit =
    if (!context.currentBuffer.hasRemaining()) {
      context.fullBuffers += context.currentBuffer
      context.currentBuffer = null
    }



  /**
   * Calculates a total free space in all the buffers.
   */
  private def getFreeSpace(context : T) : Int = {
    var res = 0

    if (context.currentBuffer != null)
      res += context.currentBuffer.remaining()

    val itr = context.freeBuffers.iterator
    while (itr.hasNext)
      res += itr.next.capacity()

    res
  }



  /**
   * Calculates length of the message length.
   * @throws IllegalArgumentException if <code>length</code> is
   * greater than maximal allowed message length.
   */
  private def lengthOfLength(length : Int) : Int =
    if (length < 0x3F)
      1
    else if (length < 0x3FFF)
      2
    else if (length < 0x3FFFFF)
      3
    else if (length < 0x3FFFFFFF)
      4
    else
      throw new IllegalArgumentException(
        "Required message length " + length +
        " is greater than allowed length of " + 0x3FFFFF)
}
