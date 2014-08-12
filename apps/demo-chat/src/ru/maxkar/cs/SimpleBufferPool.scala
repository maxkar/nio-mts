package ru.maxkar.cs


import java.nio._

/** Buffer pool. */
final class SimpleBufferPool(bufferSize : Int) {
  val queue = new scala.collection.mutable.Queue[ByteBuffer]()

  def get() : ByteBuffer =
    if (queue.isEmpty)
      ByteBuffer.allocateDirect(bufferSize)
    else
      queue.dequeue()


  def release(buf : ByteBuffer) : Unit = {
    buf.clear()
    queue += buf
  }
}
