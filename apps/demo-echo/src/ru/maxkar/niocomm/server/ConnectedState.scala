package ru.maxkar.niocomm.server


import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.ReadableByteChannel

import ru.maxkar.niocomm._


/**
 * State definition for the connected client.
 */
final object ConnectedState {

   /** Delay before sending a next ping request. */
   val pingRequestDelay = 10000

   /** Delay before receiving a ping reply. */
   val pingResponceDelay = 1000

  /** Context for the connected state.
   * @param readBuffer input buffer.
   * @param messageReadContext context for message reading.
   * @param messageWriteContext context for message writing.
   * @param outContext data output context.
   * @param nextPingRequest time for the next ping request.
   */
  final class T private[ConnectedState](
      private[ConnectedState] val readBuffer : ByteBuffer,
      private[ConnectedState] val messageReadContext : MessageReader.T,
      private[ConnectedState] val writeContext : MessageWriter.T,
      private[ConnectedState] val outContext : BufferWriter.T,
      private[ConnectedState] var nextPingRequest : Long) {

    /** Time of the next expected ping response. */
    private[ConnectedState] var nextPingResponse : Long = 0
  }



  /**
   * Creates a new communication context.
   */
  def context(now : Long, buf1 : ByteBuffer, buf2 : ByteBuffer, buf3 : ByteBuffer) : T = {
    val res = new T(
      buf1,
      MessageReader.unboundedContext,
      MessageWriter.unboundedContext,
      BufferWriter.unboundedContext,
      now + pingRequestDelay
    )


    MessageWriter.addBuffer(res.writeContext, buf2)
    MessageWriter.addBuffer(res.writeContext, buf3)

    res
  }



  /** Performs a write operation. */
  def doWrite(key : SelectionKey, now : Long, context : T) : Unit = {
    BufferWriter.smartWriteToSelectionKey(context.outContext, key)


    var noMoreBufs = false
    while (noMoreBufs) {
      val buf = BufferWriter.releaseBuffer(context.outContext)
      if (buf == null)
        noMoreBufs = true
      else
        MessageWriter.addBuffer(context.writeContext, buf)
    }


    if (BufferWriter.hasPendingWrites(context.outContext))
      return

    val lastBuffer = MessageWriter.getReadyBuffer(context.writeContext)

    if (lastBuffer != null)
      if (BufferWriter.smartEnqueueAndWriteToSelectionKey(
          context.outContext, lastBuffer, key))
        MessageWriter.addBuffer(context.writeContext,
          BufferWriter.releaseBuffer(context.outContext))
  }



  /** Performs a read operation. */
  def doRead(buf : ByteBuffer ⇒ Unit, key : SelectionKey, now : Long, context : T) : Unit = {
    val bytes =
      key.channel().asInstanceOf[ReadableByteChannel].read(
        context.readBuffer)

    if (bytes == 0)
      return
    else if (bytes < 0) {
      MessageReader.finish(context.messageReadContext)
      release(buf, context)
      key.channel().close()
      return
    }


    MessageReader.fillAllMessages(context.messageReadContext, context.readBuffer, 10000)

    while (true) {
      val msg = MessageReader.getNextMessageAsBytes(context.messageReadContext)
      if (msg == null) {
        var isReadyToWrite = true
        var haveWrites = true

        while (haveWrites) {
          var buf = MessageWriter.getFullBuffer(context.writeContext)
          if (buf == null)
            haveWrites = false
          else
            isReadyToWrite &=
              BufferWriter.smartEnqueueAndWriteToSelectionKey(
                context.outContext, buf, key)
        }


        if (isReadyToWrite) {
          val buf = MessageWriter.getReadyBuffer(context.writeContext)
          if (buf != null) {
              BufferWriter.smartEnqueueAndWriteToSelectionKey(
                context.outContext, buf, key)
          }
        }


        var hasCopy = true
        while (hasCopy) {
          val rbuf = BufferWriter.releaseBuffer(context.outContext)
          if (rbuf == null)
            hasCopy = false
          else
            MessageWriter.addBuffer(context.writeContext, rbuf)
        }

        return
      }

      if (msg.length == 0)
        throw new IOException("Empty message detected")

      msg(0) match {
        case 1 ⇒
          context.nextPingResponse = 0
          context.nextPingRequest = now + pingRequestDelay
        case 0 ⇒
          val req = new Array[Byte](1)
          req(0) = 1
          MessageWriter.writeMessage(context.writeContext, req)
        case 2 ⇒
          MessageWriter.writeMessage(context.writeContext, msg)
      }
    }
  }


  /**
   * Performs a ping operation.
   */
  def doPing(key : SelectionKey, now : Long, context : T) : Unit = {
    if (context.nextPingResponse > 0) {
      if (context.nextPingResponse < now)
        throw new IOException("Ping expired")
      return
    }

    if (context.nextPingRequest > now) {
      val req = new Array[Byte](1)
      req(0) = 1
      MessageWriter.writeMessage(context.writeContext, req)
      context.nextPingResponse = now + pingResponceDelay

      if (!BufferWriter.hasPendingWrites(context.outContext)) {
        val buf = MessageWriter.getReadyBuffer(context.writeContext)
        if (BufferWriter.smartEnqueueAndWriteToSelectionKey(
            context.outContext, buf, key))
          MessageWriter.addBuffer(context.writeContext,
            BufferWriter.releaseBuffer(context.outContext))
      }
    }
  }



  /**
   * Performs operation in a "safe" manner. If any exception is
   * thrown, closes the associated channel and releases the resources.
   * @param bufferReleaseCallback callback to inoke in the case of the
   * exception.
   * @param operation operation to perform in a safe manner.
   * @param key active selection key.
   * @param now operation time.
   * @param context operation context.
   */
  def doSafe(
        bufferReleaseCallback : ByteBuffer ⇒ Unit,
        operation : (SelectionKey, Long, T) ⇒ Unit)(
        key : SelectionKey,
        now : Long,
        context : T)
      : Unit = {
    try {
      operation(key, now, context)
    } catch {
      case t : Throwable ⇒
        try {
          release(bufferReleaseCallback, context)
        } finally {
          key.channel().close()
        }
        throw t
    }
  }



  /**
   * Releases context and deallocates data buffers.
   */
  def release(
        bufferReleaseCallback : ByteBuffer ⇒ Unit,
        context : T)
      : Unit = {
    bufferReleaseCallback(context.readBuffer)
    MessageWriter.clear(context.writeContext)
      .foreach(bufferReleaseCallback)
    BufferWriter.clear(context.outContext)
      .foreach(bufferReleaseCallback)
  }
}
