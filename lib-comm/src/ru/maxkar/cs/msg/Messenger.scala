package ru.maxkar.cs.msg

import java.nio.channels._
import java.nio._


/**
 * Messaging implementation. Works with the multiplexor.
 * By deufalt all the methods shold be called on the multiplexor
 * thread.
 */
final class Messenger private(
      key : SelectionKey,
      writer : MessageWriter,
      callback : (Messenger, Array[Byte]) ⇒ Unit,
      closeCallback : () ⇒ Unit) {

  var closed = false

  def send(message : Array[Byte]) : Unit =
    writer.send(message)

  private def onMessage(data : Array[Byte]) : Unit =
    callback(this, data)

  def close() : Unit = {
    if (closed)
      return
    closed = true
    try {
      key.channel().close()
    } finally {
      try {
        key.cancel()
      } finally {
        if (closeCallback != null)
          closeCallback()
      }
    }
  }
}


/**
 * Messenger companion.
 */
object Messenger {
  /**
   * Creates a new messenger.
   * @param item selection registration for this messenger.
   * @param readBuf read buffer.
   * @param writeBuf1 first write buffer.
   * @param writeBuf2 second write buffer. Should have a same size as first.
   * @param messageHandler handler for incoming messages.
   * @param messageLimit maximal message size for receiving the message.
   *   Outgoing messeges are not limited by this but they are limited by the
   *   outgoing queue.
   * @param pingTimeout timeout after which a connection is closed.
   * @param pingInterval interval between two pings.
   * @param closeHandler handler to invoke after messenger is closed.
   */
  def bind(
        item : SelectionKey,
        readBuf : ByteBuffer,
        writeBuf1 : ByteBuffer,
        writeBuf2 : ByteBuffer,
        messageHandler : (Messenger, Array[Byte]) ⇒ Unit,
        messageLimit : Int = 0xFFFF,
        pingTimeout : Int = 10000,
        pingInterval : Int = 30000,
        closeHandler : () ⇒ Unit = null) :
      Messenger = {

    val writer = new MessageWriter(writeBuf1, writeBuf2, item)
    val pinger = new Pinger(pingTimeout, pingInterval, writer.ping)
    val messenger = new Messenger(item, writer, messageHandler, closeHandler)
    val reader = new MessageReader(
        readBuf, messenger.onMessage, writer.pingReply, pinger.resetPing, messageLimit)

    val handler = new MessageHandler(reader, writer, pinger, messenger.close)

    item.interestOps(item.interestOps() | SelectionKey.OP_READ)
    item.attach(handler)

    messenger
  }
}
