package ru.maxkar.niocomm

import java.io.IOException

import java.io.InputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.ReadableByteChannel
import java.nio.channels.SocketChannel
import java.nio.channels.SelectionKey


/**
 * Message input and output node. Can both read messages from
 * the channel and write messages to the output stream.
 * This class represents handy API for pure message-oriented
 * protocols (each submission can be represented as a message).
 * General IO workflow is: <ol>
 *   <li> Update buffer from the selection key.
 *   <li> Process messages by pulling received data and sending
 *        new messages.
 *   <li> Write buffer/context to the selection key.
 * </ol>
 * @param readBuffer data reading buffer.
 * @param messageLimit maximal message size.
 * @param messageInContext message reading context.
 * @param messageOutContext message output context.
 * @param bufferOutContext buffer output context.
 */
final class MessageIO private(
    private var readBuffer : ByteBuffer,
    messageLimit : Int,
    messageInContext : MessageReader,
    messageOutContext : MessageWriter,
    bufferOutContext : BufferWriter) {



  /**
   * Flag set when EOF is reached.
   */
  private var hadEOF : Boolean = false



  /**
   * Flag indicating that output shutdown request was received.
   */
  private var writeClosing : Boolean = false



  /**
   * Flag indicating that output was succesfully shut down.
   */
  private var writeClosed : Boolean = false



  /** Messages as byte stream. */
  val inBytes : Source[Array[Byte]] = messageInContext.byteMessages



  /** Messages as input streams. */
  val inStreams : Source[InputStream] = messageInContext.streamMessages



  /** Data message stream. */
  val inData : Source[DataInputStream] = messageInContext.dataMessages



  /** Byte output channel. */
  val outBytes : Sink[Array[Byte]] =
    adaptSink(messageOutContext.byteMessages)



  /** Stream output channel. */
  val outStreams : Sink[OutputStream ⇒ Unit] =
    adaptSink(messageOutContext.streamFactories)



  /** Data stream output channel. */
  val outData : Sink[DataOutputStream ⇒ Unit] =
    adaptSink(messageOutContext.dataFactories)



  /**
   * Checks if read is complete. Read is complete when there are
   * no pending messages and end of input was reached.
   * @return <code>true</code> if all input messages are processed
   *   and EOF is reached.
   */
  def readComplete() : Boolean =
    hadEOF && !inBytes.hasNext



  /**
   * Checks if the write is complete. This means that all data output
   * was flushed and output was shut down. You need to call
   * <code>shutdownOutput()</code> at least once to enable this method
   * to return <code>true</code>.
   */
  def writeComplete() : Boolean = writeClosed



  /**
   * Checks if all the IO is complete. This means that both reads and
   * writes are complete.
   */
  def ioComplete() : Boolean = readComplete && writeComplete



  /**
   * Updates this IO handler by reading data from the input socket.
   * Returns immediately if EOF was observed by this context.
   */
  def updateFrom(key : SelectionKey) : Unit = {
    ensureOpen()

    if (hadEOF)
      return

    val channel = key.channel.asInstanceOf[ReadableByteChannel]
    while (true) {
      readBuffer.clear

      val bytes = channel.read(readBuffer)

      if (bytes < 0) {
        hadEOF = true
        messageInContext.finish
        return
      }
      if (bytes == 0)
        return

      readBuffer.flip
      messageInContext.fillAll(readBuffer, messageLimit)
    }
  }



  /**
   * Writes content of this buffer into the destination buffer.
   */
  def writeTo(key : SelectionKey) : Unit = {
    ensureOpen()

    if (writeClosed)
      return

    bufferOutContext.queue ++= messageOutContext.fullBuffers
    bufferOutContext.writeTo(key)

    if (!bufferOutContext.hasPendingWrites) {
      bufferOutContext.queue ++= messageOutContext.readyBuffers
      bufferOutContext.writeTo(key)
    }

    messageOutContext.workBuffers ++= bufferOutContext.written

    if (writeClosing && !bufferOutContext.hasPendingWrites) {
      writeClosed = true
      val chan = key.channel
      if (chan.isInstanceOf[SocketChannel])
        chan.asInstanceOf[SocketChannel].shutdownOutput()
    }
  }



  /**
   * Mark the output as "shut down". Output will be shut down
   * after all data are written to the buffer. A writeTo operation
   * should be called after a call to this method.
   */
  def shutdownOutput() : Unit = {
    ensureOpen()
    writeClosing = true
  }



  /**
   * Closes this IO handler and returns list of buffers.
   * Further selector operations will cause IOException.
   * It is safe to call close multiple times.
   * @return all buffers that was associated with this IO handler.
   */
  def close() : Seq[ByteBuffer] = {
    if (readBuffer == null)
      return Seq.empty

    val tmp = readBuffer
    readBuffer = null
    messageOutContext.clear ++ bufferOutContext.clear :+ tmp
  }



  /** Ensures that this IO is open. */
  private def ensureOpen() : Unit =
    if (readBuffer == null)
      throw new MessageIO.IOClosed()



  /** Wraps a sink in the close-safe manner. */
  private def adaptSink[T](sink : Sink[T]) : Sink[T] =
    Sink(x ⇒ {
      ensureOpen()
      if (writeClosing)
        throw new MessageIO.OutputClosing()
      sink += x
    })
}



/**
 * Message IO companion object.
 */
final object MessageIO {

  /** Exception denoting a closed message IO. */
  final class IOClosed extends IOException("IO handler is closed")

  /** Exception denoting a closing output. */
  final class OutputClosing extends
    IOException("Data output is closing now")


  /**
   * Creates a new message IO handler.
   * @param readBuffer data read buffer.
   * @param inMessageLimit maximum allowed size of incoming message.
   * @param outBuf1 first output worker buffer.
   * @param outBuf2 second output worker buffer.
   * @param restOut additional output worker buffers.
   */
  def create(
        readBuffer : ByteBuffer, inMessageLimit : Int,
        outBuf1 : ByteBuffer, outBuf2 : ByteBuffer,
        restOut : ByteBuffer*)
      : MessageIO =
    new MessageIO(
      readBuffer, inMessageLimit,
      MessageReader.create,
      MessageWriter.create(outBuf1 +: outBuf2 +: restOut : _*),
      BufferWriter.create)
}
