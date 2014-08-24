package ru.maxkar.niocomm

import java.nio.ByteBuffer
import java.nio.channels.SelectableChannel
import java.nio.channels.SelectionKey
import java.nio.channels.WritableByteChannel

import scala.collection.mutable.Queue



/**
 * Writer for byte buffers. Writer is push/pull driven. Write
 * requests are added to the "queue" sink. Free buffers are
 * available via "written" source. Items are released in order
 * they was enqueued.
 * <p>Typical outer flow is as follows:<ol>
 *  <li>Enqueue new items into the writer.
 *  <li>Flush this buffer into a stream
 *  <li>Retrieve released buffers.
 * </ol>
 */
final class BufferWriter private () {

  /** Queue of buffers to write. */
  private val writeQueue = new Queue[ByteBuffer]


  /**
   * Queue of written (empty) buffers.
   * Buffer order in this queue matches the write order
   * of original buffers.
   */
  private val releaseQueue = new Queue[ByteBuffer]



  /**
   * Write requests sink. Write requests are enqueued
   * and will be fullfilled by <code>writeTo</code>
   * operations. Completely written buffers will be available
   * in the <code>written</code> source.
   * <p>All added buffers should be in "write" position.
   */
  val queue : Sink[ByteBuffer] = Sink(writeQueue.+=)



  /**
   * Source of written (and released) buffers.
   * Buffers will be returned in the same order as they was
   * enqueued.
   */
  val written : Source[ByteBuffer] =
    Source(() ⇒ !releaseQueue.isEmpty, releaseQueue.dequeue)



  /**
   * Checks if there are pending writes. This mean that
   * there is a buffer in the write queue.
   * @return <code>true</code> iff there is a non-written buffer.
   */
  def hasPendingWrites() : Boolean = !writeQueue.isEmpty



  /**
   * Writes this context into the writable channel. Completely
   * written buffers will be available in the <code>written</code> source.
   * @param channel destination channel.
   */
  def writeTo(channel : WritableByteChannel) : Unit =
    while (!writeQueue.isEmpty) {
      val buf = writeQueue.head

      channel.write(buf)
      if (buf.hasRemaining())
        return

      releaseQueue += writeQueue.dequeue
    }



  /**
   * Writes this context to a channel associated with the selection key.
   * Updates a key interested ops based on the buffer state. Adds write
   * to the interested ops if there are pending writes. Removes write op
   * from interested ops if there are no pending writes.
   * @param key selection key with the associated writable channel.
   */
  def writeTo(key : SelectionKey) : Unit = {
    writeTo(key.channel().asInstanceOf[WritableByteChannel])
    if (hasPendingWrites)
      key.interestOps(key.interestOps | SelectionKey.OP_WRITE)
    else
      key.interestOps(key.interestOps & ~SelectionKey.OP_WRITE)
  }



  /**
   * Clears all the queues and releases all the buffers.
   * This operation can be used by close/release code to
   * return all buffers to a resource management system.
   */
  def clear() : Seq[ByteBuffer] =
    releaseQueue.drop(0) ++ writeQueue.drop(0)
}




/**
 * Buffer writer companion.
 */
final object BufferWriter {

  /**
   * Creates a new unbounded writer. This writer has no synthetic
   * size limitations and can accomadate any number of buffers allowed by
   * memory settings.
   */
  def create() : BufferWriter = new BufferWriter()
}

