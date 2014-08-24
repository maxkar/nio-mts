package ru.maxkar.niocomm

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer

import scala.collection.mutable.Queue

/**
 * Message formatter and writer. Maximal message size il limited to
 * 2^30 bytes.
 * <p>Typical message writer flow is as follow:<ol>
 *   <li>Add new messages to a writer.
 *   <li>Write all full buffers.
 *   <li>If output is possible (no pending write op),
 *     write a ready buffer.
 *   <li>Return all written buffers to this writer.
 * </ol>
 */
final class MessageWriter private() {


  /** Working queue. */
  private val workQueue = new Queue[ByteBuffer]



  /** Full write buffers. */
  private val fullQueue = new Queue[ByteBuffer]



  /** Source for the ready (but incomplete) item. */
  private val currentReadyBuffer = Source.apply[ByteBuffer](
    () ⇒ !workQueue.isEmpty && workQueue.head.position > 0,
    () ⇒ {
      val res = workQueue.dequeue
      res.flip
      res
    })



  /** Sink for work buffers. */
  val workBuffers : Sink[ByteBuffer] = Sink(buf ⇒ {
      buf.clear
      workQueue += buf
    })



  /** Sink for byte-array messages. */
  val byteMessages : Sink[Array[Byte]] = Sink(addBytes)



  /** Sink for output stream writers (message factories). */
  val streamFactories : Sink[OutputStream ⇒ Unit] = Sink(addStreamWriter)



  /** Sink for message factories (data writers). */
  val dataFactories : Sink[DataOutputStream ⇒ Unit] = Sink(addDataWriter)



  /** Full buffers (no more free space). */
  val fullBuffers : Source[ByteBuffer] = Source(
    () ⇒ !fullQueue.isEmpty,
    fullQueue.dequeue)



  /** Ready buffers (buffer with some data in it). */
  val readyBuffers : Source[ByteBuffer] =
    Source.concat(fullBuffers, currentReadyBuffer)



  /** Clears current writer.  Returns list of used buffers.  */
  def clear() : Seq[ByteBuffer] =
    fullQueue.drop(0) ++ workQueue.drop(0)



  /** Adds a data writer. */
  private def addDataWriter(writer : DataOutputStream ⇒ Unit) : Unit = {
    var data = new ByteArrayOutputStream()
    writer(new DataOutputStream(data))
    addBytes(data.toByteArray)
  }



  /** Adds a stream writer.*/
  private def addStreamWriter(writer : OutputStream ⇒ Unit) : Unit = {
    var data = new ByteArrayOutputStream()
    writer(data)
    addBytes(data.toByteArray)
  }



  /** Adds a new message to this writer.  */
  private def addBytes(data : Array[Byte]) : Unit = {
    val len = data.length
    val lol = lengthOfLength(len)
    ensureSpaceFor(data.length + lol)

    lol match {
      case 1 ⇒
        writeByte(len)
      case 2 ⇒
        writeByte((len >> 8) | 0x40)
        writeByte(len)
      case 3 ⇒
        writeByte((len >> 16) | 0x80)
        writeByte(len >> 8)
        writeByte(len)
      case 4 ⇒
        writeByte((len >> 24) | 0xC0)
        writeByte(len >> 16)
        writeByte(len >> 8)
        writeByte(len)
    }

    var writePtr = 0
    while (writePtr < len) {
      val buf = workQueue.head
      val toWrite = Math.min(len - writePtr, buf.remaining)
      buf.put(data, writePtr, toWrite)
      writePtr += toWrite
      if (!buf.hasRemaining) {
        buf.flip
        fullQueue += workQueue.dequeue
      }
    }
  }



  /**
   * Checks if this writer have enough space for the specified
   * amount of data.
   */
  private def ensureSpaceFor(target : Int) : Unit = {
    var remaining = target

    val itr = workQueue.iterator
    while (remaining > 0 && itr.hasNext)
      remaining -= itr.next.remaining

    if (remaining > 0)
      throw new MessageWriter.NoWriteSpace(
        target, target - remaining)
  }



  /** Writes one byte. */
  private def writeByte(byte : Int) : Unit = {
    val buf = workQueue.head
    buf.put(byte.asInstanceOf[Byte])
    if (!buf.hasRemaining) {
      buf.flip
      fullQueue += workQueue.dequeue
    }
  }



  /**
   * Calculates length of the message length.
   * @throws IllegalArgumentException if <code>length</code> is
   * greater than maximal allowed message length.
   */
  private def lengthOfLength(length : Int) : Int =
    if (length < 0x3F) 1
    else if (length < 0x3FFF) 2
    else if (length < 0x3FFFFF) 3
    else if (length < 0x3FFFFFFF) 4
    else throw new IllegalArgumentException(
        "Required message length " + length +
        " is greater than allowed length of " + 0x3FFFFF)
}




/**
 * Message writer companion.
 */
final object MessageWriter {


  /**
   * Exception denoting that there are no space to write the
   * message.
   */
  final class NoWriteSpace(val required : Int, val free : Int)
      extends IOException(
        "Insufficient buffer space, required " +
        required + " but have only " + free)


  /**
   * Creates a new message writer. Returned writer have no
   * synthetic restrictions on queue lengths.
   * @param workBuffers initial work buffers for the writer.
   */
  def create(workBuffers : ByteBuffer*) : MessageWriter = {
    val res = new MessageWriter()
    res.workBuffers ++= workBuffers
    res
  }
}
