package ru.maxkar.niocomm

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer

import scala.collection.mutable.Queue

/**
 * Transport-level message reader compatible with message-writer.
 * This reader updates context with the new data/messages.
 */
final object MessageReader {

  /** Context type. */
  final class T private[MessageReader]() {
    /** Ready messages. */
    private[MessageReader] val messageQueue = new Queue[Array[Byte]]

    /** Number of bytes of length to read. */
    private[MessageReader] var lengthRemainingBytes : Int = 0

    /** Total length of the next message. */
    private[MessageReader] var messageLength : Int = 0

    /** Number of message bytes already read. */
    private[MessageReader] var readBytes : Int = 0

    /**
     * Current buffer. Set to some value if buffer is not
     * ready yet. Set to <code>null</code> between messages or
     * while message length is not ready yet.
     */
    private[MessageReader] var nextMessage : Array[Byte] = null
  }



  /**
   * Exception denoting violation of allowed message length.
   */
  final class AllowedLengthExceeded(length : Int, limit : Int)
      extends IOException("Message length " + length +
        " exceedes an allowed limit of " + limit)



  /**
   * Exception denoting missing bytes before the stream EOF.
   * @param minMissingBytes minimal number of missing bytes.
   */
  final class DataMissing(minMissingBytes : Int)
      extends IOException("Unexpected End Of Steam. " +
        " At least " + minMissingBytes + " bytes is missing.")




  /**
   * Creates a new context. Returned context have no synthetic
   * restriction for number of read messages.
   */
  def unboundedContext() : T = new T()



  /**
   * Checks if context have at least one ready message.
   * @param context context to check.
   * @return <code>true</code> iff context have a ready message.
   */
  def hasMessage(context : T) : Boolean =
    !context.messageQueue.isEmpty



  /**
   * Checks if context is inside "incomplete" message. This
   * means that some bytes are required to finish message.
   * This message checks only an "active" message, not an
   * ability to read next message.
   * @param context context to check.
   * @return <code>true</code> iff context have an incopmlete read.
   * This means that some data should be added to finish the message.
   */
  def hasIncompleteRead(context : T) : Boolean =
    context.nextMessage != null || context.lengthRemainingBytes > 0



  /**
   * Checks if context is empty (has neither untaken messages nor
   * unfinished message). If this method returns <code>true</code>,
   * then this buffer can represent an "EOF" state. Value
   * <code>false</code> means that context can not represent valid
   * end of stream either because some messager are not consumed or
   * because current buffer is "inside" the message payload (i.e.
   * context have part of incomplete message).
   * @param context context to check.
   * @return <code>true</code> iff there are no unprocessed messages
   * and no incomplete message.
   */
  def isEmpty(context : T) : Boolean =
    !hasMessage(context) && !hasIncompleteRead(context)



  /**
   * Updates a context using provided buffer. Can complete/create
   * a new message and put in in queue of unprocessed messages.
   * @param context context to update.
   * @param data data representing a stream payload.
   * @param maxLength maximal message length. This parameter do not
   * affect current (incomplete) message if buffer is allocated for it.
   * @return <code>true</code> iff at least one new message was
   * completed and put into the queue. This method will return
   * <code>false</code> if no new messages were created even if
   * there are unprocessed messages.
   * @throws AllowedLengthExceeded if message length for a new message exceeds
   * <code>maxLength</code> bytes.
   */
  def fillAllMessages(context : T, data : ByteBuffer, maxLength : Int = Integer.MAX_VALUE) : Boolean = {
    var updated = false
    while (fillOneMessage(context, data, maxLength))
      updated = true
    updated
  }



  /**
   * Fills buffer with one (and only one) message. Thism method may be handy if
   * user want to process message one-by-one and some messages may affect
   * <code>maxLength</code> for next messages.
   * @param context context to update.
   * @param data data to update context from.
   * @param maxLength maximal message length.
   * @return <code>true</code> iff new message was completed
   * and put into the release queue.
   */
  def fillOneMessage(context : T, data : ByteBuffer, maxLength : Int)
      : Boolean = {
    if (!prepareBuffer(context, data, maxLength))
      return false

    val toRead = Math.min(
      context.messageLength - context.readBytes,
      data.remaining())

    if (toRead > 0) {
      data.get(context.nextMessage, context.readBytes, toRead)
      context.readBytes += toRead
    }

    if (context.readBytes < context.messageLength)
      return false

    context.messageQueue += context.nextMessage
    context.nextMessage = null
    context.readBytes = 0
    return true
  }



  /**
   * Finishes a data processing by ensuring "no-messages" condition.
   * Does not "close" context for further updates.
   * @param context context to "finish".
   * @throws DataMissing if context have an incomplete message.
   */
  def finish(context : T) : Unit = {
    if (context.lengthRemainingBytes > 0)
      throw new DataMissing(context.lengthRemainingBytes)
    if (context.nextMessage != null)
      throw new DataMissing(context.messageLength - context.readBytes)
  }



  /**
   * Updates all the messages and ensures valid EOF position.
   * @param context context to update.
   * @param data data to update context with.
   * @param maxLength maximal allowed message length.
   * @return <code>true</code> iff at least one new message was
   * completed and put into the queue. This method will return
   * <code>false</code> if no new messages were created even if
   * there are unprocessed messages.
   * @throws AllowedLengthExceeded if message length for a new message exceeds
   * <code>maxLength</code> bytes.
   * @throws DataMissing if context have an incomplete message.
   */
  def fillAllAndFinish(context : T, data : ByteBuffer, maxLength : Int) : Boolean = {
    val res = fillAllMessages(context, data, maxLength)
    finish(context)
    res
  }



  /**
   * Retrieves next message as byte array.
   * @param context context to extract data from.
   * @return next message or <code>null</code> if there are no messages.
   */
  def getNextMessageAsBytes(context : T) : Array[Byte] =
    if (context.messageQueue.isEmpty)
      null
    else
      context.messageQueue.dequeue



  /**
   * Returns next message as a stream.
   * @param context context to get message from.
   * @return next message or <code>null</code> if there are no messages.
   */
  def getNextMessageAsStream(context : T) : InputStream = {
    val peer = getNextMessageAsBytes(context)
    if (peer == null)
      null
    else
      new ByteArrayInputStream(peer)
  }



  /**
   * Returns next message as data input.
   * @param context context to get message from.
   * @return next message or <code>null</code> if there are no messages.
   */
  def getNextMessageAsDataInput(context : T) : DataInputStream = {
    val peer = getNextMessageAsStream(context)
    if (peer == null)
      null
    else
      new DataInputStream(peer)
  }



  /**
   * Performs a "final" message read and returns message as
   * array of bytes. Enusres that context denotes a valid EOF
   * position when no more messages are available.
   * @param context context to extract data from.
   * @return next message or <code>null</code> if there are no messages.
   * @throws DataMissing if there are no messages but context still
   * have part of incomplete message.
   */
  def finishMessageAsBytes(context : T) : Array[Byte] =
    finishMessageBy(context, getNextMessageAsBytes)



  /**
   * Performs a "final" message read and returns message as
   * input stream. Enusres that context denotes a valid EOF
   * position when no more messages are available.
   * @param context context to extract data from.
   * @return next message or <code>null</code> if there are no messages.
   * @throws DataMissing if there are no messages but context still
   * have part of incomplete message.
   */
  def finishMessageAsStream(context : T) : InputStream =
    finishMessageBy(context, getNextMessageAsStream)



  /**
   * Performs a "final" message read and returns message as
   * data input stream. Enusres that context denotes a valid EOF
   * position when no more messages are available.
   * @param context context to extract data from.
   * @return next message or <code>null</code> if there are no messages.
   * @throws DataMissing if there are no messages but context still
   * have part of incomplete message.
   */
  def finishMessageAsDataInput(context : T) : DataInputStream =
    finishMessageBy(context, getNextMessageAsDataInput)



  /**
   * Finishes a read with the given message extractor.
   * @param context context to finish.
   * @param exctactor function to exctact message from context.
   * @return next message.
   */
  private def finishMessageBy[R <: AnyRef](
        context : T,
        extractor : T ⇒ R)
      : R = {
    val res = extractor(context)
    if (res == null)
      finish(context)
    res
  }




  /**
   * Tries to prepare a next data buffer. If buffer is ready, does
   * nothing.
   * @param context context to update.
   * @param data data to update context from.
   * @param maxLength maximal allowed message length.
   * @return <code>true</code> iff buffer is ready to receive next portion of data.
   */
  private def prepareBuffer(context : T, data : ByteBuffer, maxLength : Int) : Boolean = {
    if (context.nextMessage != null)
      return true

    if (!data.hasRemaining)
      return false

    if (context.lengthRemainingBytes == 0) {
      val headByte = data.get() & 0xFF
      context.lengthRemainingBytes = headByte >> 6
      context.messageLength = headByte & 0x3F
    }

    while (data.hasRemaining && context.lengthRemainingBytes > 0) {
      context.messageLength =
        (context.messageLength << 8) | (data.get() & 0xFF)
      context.lengthRemainingBytes -= 1
    }

    if (context.lengthRemainingBytes > 0)
      return false

    if (context.messageLength > maxLength)
      throw new AllowedLengthExceeded(context.messageLength, maxLength)

    context.nextMessage = new Array[Byte](context.messageLength)
    return true
  }
}
