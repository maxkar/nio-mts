package ru.maxkar.niocomm.chat.server

import java.nio.ByteBuffer
import scala.collection.mutable.Queue


/**
 * Buffer pool implementation.
 */
private[server] final class BufferPool(size : Int) {
  private val queue = new Queue[ByteBuffer]


  /** Returns buffers to the pool. */
  def ++=(buffers : Seq[ByteBuffer]) : Unit = queue ++= buffers


  /** Allocates next buffer. */
  def allocate() : ByteBuffer =
    if (queue.isEmpty)
      ByteBuffer.allocateDirect(size)
    else
      queue.dequeue()
}
