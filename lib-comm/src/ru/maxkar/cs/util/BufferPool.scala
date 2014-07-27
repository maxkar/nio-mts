package ru.maxkar.cs.util

import scala.collection.mutable.Queue

import java.nio.ByteBuffer

/** Single-threaded byte buffer pool. */
final class BufferPool(size : Int) {

  type T = (ByteBuffer, ByteBuffer, ByteBuffer, () ⇒ Unit)
  /** Allocated buffers. */
  private val items = new Queue[T]

  def get() : T = {
    if (items.isEmpty)
      allocate()
    else
      items.dequeue()
  }

  private def allocate() : T = {
    val b1 = ByteBuffer.allocateDirect(size)
    val b2 = ByteBuffer.allocateDirect(size)
    val b3 = ByteBuffer.allocateDirect(size)

    var res : T = null
    res = (b1, b2, b3, () ⇒ {
      b1.clear()
      b2.clear()
      b3.clear()
      items += res
    })
    res
  }
}
