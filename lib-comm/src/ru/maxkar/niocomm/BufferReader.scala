package ru.maxkar.niocomm

import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SelectionKey

import scala.collection.mutable.Queue

/**
 * Reader for the byte buffer. This buffer is push/pull driven.
 * Empty buffers are pushed into this reader. Buffers with data
 * are pulled from the reader.
 * <p>Reader context have two queues. They are "empty buffers"
 * queue and "full buffers" queue. There is also an "active" buffer.
 * That buffer can contain some data but is not full yet.
 */
final object BufferReader {

  /** Context type. */
  final class T private[BufferReader] () {
    /** Queue of full buffers. */
    private[BufferReader] val fullQueue = new Queue[ByteBuffer]

    /** Queue of empty buffers. */
    private[BufferReader] val emptyQueue = new Queue[ByteBuffer]

    /** Active read buffer. */
    private[BufferReader] var activeBuffer : ByteBuffer = null
  }



  /**
   * Creates a read context. This context have no
   * synthetic limits on number of queued buffers.
   */
  def unboundedContext() : T = new T()



  /**
   * Checks if a context have some empty space (which can
   * be filled by read operation).
   * @return true iff this context can accept at least one
   *  byte.
   */
  def hasSpace(context : T) : Boolean =
    context.activeBuffer != null || !context.emptyQueue.isEmpty



  /**
   * Checks if context have a full buffer. "Full" buffer is a buffer
   * filled up to its capacity.
   */
  def hasFullBuffer(context : T) : Boolean =
    !context.fullQueue.isEmpty


  /**
   * Checks if context have a "ready" buffer. Ready buffer is
   * a buffer with some data. This can be a buffer in "full" queue
   * or a current buffer if it have some data in it.
   * @return true iff there is a ready buffer.
   */
  def haveReadyBuffer(context : T) : Boolean =
    !context.fullQueue.isEmpty ||
    (context.activeBuffer != null && context.activeBuffer.position() > 0)



  /**
   * Reads data from channel into the context. Filled
   * buffers are moved into the full queue.
   * @param context context to put data to.
   * @param channel channel to read.
   * @return <code>true</code> iff EOF was reached during this read.
   */
  def readFromChannel(context : T, channel : ReadableByteChannel) : Boolean = {
    while (prepareReadBuffer(context)) {
      val readStatus = channel.read(context.activeBuffer)
      if (readStatus < 0)
        return true
      else if (readStatus == 0)
        return false

      if (!releaseFullBuffer(context))
        return false
    }

    false
  }



  /**
   * Reads data from the channel associated with selection key.
   * @param context context to put data to.
   * @param key key with the readable channel.
   * @return <code>true</code> iff EOF was reached during this read.
   */
  def readFromSelectionKey(context : T, key : SelectionKey) : Boolean =
    readFromChannel(context, key.channel().asInstanceOf[ReadableByteChannel])




  /**
   * Reads data from a channel associated with the selection key.
   * Unregister channel from reading if this channel is at EOF or
   * context buffer is full.
   * @param context context to put data to.
   * @param key key with the readable channel.
   * @return <code>true</code> iff EOF was reached furing this read.
   */
  def smartReadFromSelectionKey(context : T, key : SelectionKey) : Boolean = {
    val res = readFromSelectionKey(context, key)

    if (res || !hasSpace(context))
      key.interestOps(key.interestOps() & ~SelectionKey.OP_READ)

    res
  }



  /**
   * Gets a full buffer from the context. Returned buffer is
   * "flipped" and is ready for "read" operations.
   * @param context context to extract buffer from.
   * @return next full buffer or <code>null</code> if there are no
   * full buffers.
   */
  def getFullBuffer(context : T) : ByteBuffer = {
    if (context.fullQueue.isEmpty)
      return null
    val res = context.fullQueue.dequeue
    res.flip()
    res
  }



  /**
   * Gets a ready buffer from the context. If there is a full
   * buffer, that buffer is returned. Otherwise non-full current buffer
   * can be returned but only if it have some data. In any case, buffer
   * is "flipped" and is ready for read operations.
   * @param context context to extract buffer from.
   * @return next buffer with data or <code>null</code> if there are
   * no data in the context.
   */
  def getReadyBuffer(context : T) : ByteBuffer = {
    val fullBuf = getFullBuffer(context)
    if (fullBuf != null)
      return fullBuf

    if (context.activeBuffer == null || context.activeBuffer.position == 0)
      return null

    val res = context.activeBuffer
    context.activeBuffer = null
    res.flip()
    res
  }



  /**
   * Adds a new read buffer. Passed buffer is cleared and
   * added to the list of free buffers.
   * @param context context to add buffer to.
   * @param buffer buffer to add to the context. Buffer must have
   * positive capacity.
   * @throws IllegalArgumentException if buffer have zero capacity.
   */
  def addBuffer(context : T, buffer : ByteBuffer) : Unit = {
    buffer.clear()
    if (buffer.capacity == 0)
      throw new IllegalArgumentException("Buffer must have positive capacity")
    context.emptyQueue += buffer
  }



  /**
   * Prepares a next read buffer. This method sets
   * <code>activeBuffer</code> property to a buffer with some empty
   * space. If there are no such buffers, <code>activeBuffer</code> is set to
   * <code>null</code> and method returns false.
   * @return true iff <code>activeBuffer</code> is ready and can accomodate
   * new data.
   */
  private def prepareReadBuffer(context : T) : Boolean = {
    if (context.activeBuffer != null)
      return true

    if (context.emptyQueue.isEmpty)
      return false

    context.activeBuffer = context.emptyQueue.dequeue
    true
  }



  /**
   * Releases an active buffer if it is full. Releasing means
   * adding active buffer to "full" queue and setting active
   * buffer to null.
   * @returns <code>true</code> iff active buffer was released.
   */
  private def releaseFullBuffer(context : T) : Boolean = {
    if (context.activeBuffer.hasRemaining)
      return false

    context.fullQueue += context.activeBuffer
    context.activeBuffer = null
    true
  }
}
