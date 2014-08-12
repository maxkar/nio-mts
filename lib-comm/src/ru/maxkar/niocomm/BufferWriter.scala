

import java.nio.ByteBuffer
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.WritableByteChannel

import scala.collection.mutable.Queue


/**
 * Writer for byte buffers. Writer context is push/pull driven. Socket write
 * and buffer enqueue requests are pushed from the upstream handler. Free
 * buffers are polled from the context.
 * <p>Write context has two queues. First one is used to store items
 * not written yet. Second is used to store completely written items.
 * "Free" buffers are returned in the order they was written.
 */
final object BufferWriter {

  /** Context type for buffer writer. */
  final class T private[BufferWriter]() {
    /** Queue of buffers to write. */
    private[BufferWriter] val writeQueue = new Queue[ByteBuffer]

    /**
     * Queue of written (emptied) buffers.
     * Buffer order in this queue matches the write order
     * of original buffers.
     */
    private[BufferWriter] var releaseQueue = new Queue[ByteBuffer]
  }



  /**
   * Creates a new unbounded context. This write context has no synthetic
   * size limits and can accomodate any number of buffers allowed by the
   * memory.
   */
  def unboubdedContext() : T = new T()



  /**
   * Checks if there are pending writes on the context. This means that
   * there is an enqueued non-empty buffer in the write queue.
   * @return true iff there is a pending write (non-empty buffer in the
   * write queue).
   */
  def hasPendingWrites(context : T) : Boolean =
    !context.writeQueue.isEmpty



  /**
   * Checks if there are pending releases on the context.
   * @return true iff there are non-released items.
   */
  def hasPendingReleases(context : T) : Boolean =
    !context.releaseQueue.isEmpty



  /**
   * Writes a context into the given writeable channel. Moves completed
   * source buffer to the release queue. State of the context is undefined
   * if any exception is thrown.
   * @param context context to write.
   * @param target destination channel for the buffer.
   * @return true iff at least one buffer was released into the
   * release queue. If no new buffers was released, returns false even
   * release queue is not empty.
   */
  def writeToChannel(context : T, target : WritableByteChannel) : Boolean = {
    var itemReleased = false

    while (!context.writeQueue.isEmpty) {
      val item = context.writeQueue.head

      if (item.hasRemaining)
        target.write(item)

      /* Keep item in the queue if it was not writen completely. */
      if (item.hasRemaining)
        return itemReleased

      /* Move item to the release queue. */
      itemReleased = true
      context.writeQueue.dequeue
      context.releaseQueue += item
    }

    itemReleased
  }



  /**
   * Writes a context into the writeble channel associated with che
   * selection key. Moves completed source buffers to the release queue.
   * State of the context is undefined if any exception is thrown.
   * @param context context to write.
   * @param target destination selection key. Data are written to the
   *   channel associated with the selection key.
   * @return true iff at least one buffer was released into the
   * release queue. If no new buffers was released, returns false even
   * release queue is not empty.
   */
  def writeToSelectionKey(context : T, target : SelectionKey) : Boolean =
    writeToChannel(context, target.channel.asInstanceOf[WritableByteChannel])



  /**
   * Writes a context into the writeable channel associated with the key.
   * Deregisters the channel from the write operation if there are no more items
   * in the write queue. Otherwise behass as [[writeToSelectionKey]].
   * @param context context to write.
   * @param target target selection key.
   * @return true at least one buffer was released into the release queue.
   */
  def smartWriteToSelectionKey(context : T, target : SelectionKey) : Boolean = {
    val res = writeToSelectionKey(context, target)

    if (!hasPendingWrites(context))
      target.interestOps(target.interestOps() & ~SelectionKey.OP_WRITE)

    res
  }



  /**
   * Enqueues a buffer for the write. Nonempty buffer will go to the
   * write queue. Empty buffer will go directly to release queue if there
   * are no pending writes otherwise it will also go to the write queue.
   * @param context context to enqueue item to.
   * @param target new buffer to enqueue.
   */
  def enqueue(context : T, target : ByteBuffer) : Unit = {
    if (target.hasRemaining)
      context.writeQueue += target
    else if (hasPendingWrites(context))
      context.writeQueue += target
    else
      context.releaseQueue += target
  }



  /**
   * Enqueues item and attempts to flush data if write queue was empty
   * before call to this method.
   * @param context context to enqueue item to.
   * @param target new buffer to enqueue.
   * @param channel channel to try to write the data.
   * @return true iff all the data was written and write queue is empty.
   */
  def enqueueAndWriteToChannel(
        context : T, target : ByteBuffer, channel : WritableByteChannel)
      : Boolean = {

    val shouldTryWrite = !hasPendingWrites(context)
    enqueue(context, target)

    /* If trying to write, then queue contains only one item.
     * However, empty items will go directly to the release queue
     * so write will return false, but buffer is already processed.
     */
    if (shouldTryWrite)
      writeToChannel(context, channel) || !target.hasRemaining()
    else
      false
  }



  /**
   * Enqueues item and attempts to flush data if write queue was empty
   * before call to this method.
   * @param context context to enqueue item to.
   * @param target new buffer to enqueue.
   * @param key selection key to extract target channel.
   * @return true iff all the data was written and write queue is empty.
   */
  def enqueueAndWriteToSelectionKey(
        context : T, target : ByteBuffer, key : SelectionKey)
      : Boolean =
    enqueueAndWriteToChannel(context, target,
      key.channel.asInstanceOf[WritableByteChannel])



  /**
   * Enqueues item and attempts to flush data. Similar to
   * [[enqueueAndWriteToSelectionKey]], but registers a selection key
   * to the write operation if there are some remaining data.
   */
  def smartEnqueueAndWriteToSelectionKey(
        context : T, target : ByteBuffer, key : SelectionKey)
     : Boolean = {

    val res = enqueueAndWriteToSelectionKey(context, target, key)
    if (!res)
      key.interestOps(key.interestOps() | SelectionKey.OP_WRITE)
    res
  }



  /**
   * Releases a first written but not released buffer. Buffers
   * are released in the write order.
   * @param context context to release a buffer from.
   * @return next "released" buffer or <code>null</code> if
   * there are no released buffers. Null value means that
   * either there are no buffers associated with the context
   * or that all buffers are waiting a write operation.
   */
  def releaseBuffer(context : T) : ByteBuffer =
    if (hasPendingReleases(context))
      context.releaseQueue.dequeue
    else
      null
}

