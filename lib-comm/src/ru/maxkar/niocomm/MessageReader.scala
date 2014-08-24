package ru.maxkar.niocomm

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.IOException
import java.nio.ByteBuffer

import scala.collection.mutable.Queue



/**
 * Transport message reader.
 */
final class MessageReader private () {
  /** Ready messages. */
  private val messageQueue = new Queue[Array[Byte]]

  /** Number of bytes of length to read. */
  private var lengthRemainingBytes = 0

  /** Total length of the next message. */
  private var messageLength = 0

  /** Reading position. */
  private var readPosition = 0

  /**
   * Next message buffer. Set to <code>null</code> when message
   * length is not ready yet.
   */
  private var nextMessage : Array[Byte] = null


  /** Message source which treats messages as bytes. */
  val byteMessages : Source[Array[Byte]] = Source(
    () ⇒ !messageQueue.isEmpty, messageQueue.dequeue)



  /** Message source which treats messages as input streams. */
  val streamMessages : Source[InputStream] = Source(
    () ⇒ !messageQueue.isEmpty,
    () ⇒ new ByteArrayInputStream(messageQueue.dequeue))



  /** Message source which treats messages as data streams. */
  val dataMessages : Source[DataInputStream] = Source(
    () ⇒ !messageQueue.isEmpty,
    () ⇒ new DataInputStream(
      new ByteArrayInputStream(messageQueue.dequeue)))



  /** Updates all messages from the buffer. Fully conumes the buffer. */
  def fillAll(data : ByteBuffer, maxLength : Int) : Unit = {
    while (fillOneMessage(data, maxLength)) {}
  }



  /** Updates one message from the buffer.
   * @param data data buffer to update from.
   * @return <code>true</code> if message was completed and added
   * to the message sources. False otherwise.
   */
  def fillOneMessage(data : ByteBuffer, maxLength : Int) : Boolean = {
    if (!prepareBuffer(data, maxLength))
      return false

    val toRead = Math.min(messageLength - readPosition, data.remaining)

    if (toRead > 0) {
      data.get(nextMessage, readPosition, toRead)
      readPosition += toRead
    }

    if (readPosition < messageLength)
      return false

    messageQueue += nextMessage
    nextMessage = null
    readPosition = 0
    return true
  }



  /**
   * Finishes a data processing by ensuring "no-messages" condition.
   * Does not "close" context for further updates.
   * @param context context to "finish".
   * @throws DataMissing if context have an incomplete message.
   */
  def finish() : Unit = {
    if (lengthRemainingBytes > 0)
      throw new MessageReader.DataMissing(lengthRemainingBytes)
    if (nextMessage != null)
      throw new MessageReader.DataMissing(messageLength - readPosition)
  }



  /**
   * Tries to prepare a next data buffer. If buffer is ready, does
   * nothing.
   * @param data data to update context from.
   * @param maxLength maximal allowed message length.
   * @return <code>true</code> iff buffer is ready to receive next portion of data.
   */
  private def prepareBuffer(data : ByteBuffer, maxLength : Int) : Boolean = {
    if (nextMessage != null)
      return true

    if (!data.hasRemaining)
      return false

    if (lengthRemainingBytes == 0) {
      val headByte = data.get() & 0xFF
      lengthRemainingBytes = headByte >> 6
      messageLength = headByte & 0x3F
    }

    while (data.hasRemaining && lengthRemainingBytes > 0) {
      messageLength = (messageLength << 8) | (data.get() & 0xFF)
      lengthRemainingBytes -= 1
    }

    if (lengthRemainingBytes > 0)
      return false

    if (messageLength > maxLength)
      throw new MessageReader.AllowedLengthExceeded(messageLength, maxLength)

    nextMessage = new Array[Byte](messageLength)
    return true
  }
}



/**
 * Reader companion.
 */
final object MessageReader {
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
   * Creates a new message reader. That reader do not have any
   * synthetic queue restrictions.
   */
  def create() : MessageReader = new MessageReader()
}
