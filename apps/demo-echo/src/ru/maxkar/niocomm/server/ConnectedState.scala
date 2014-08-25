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
      private[ConnectedState] val ioContext : MessageIO,
      private[ConnectedState] var nextPingRequest : Long) {

    /** Time of the next expected ping response. */
    private[ConnectedState] var nextPingResponse : Long = 0
  }



  /**
   * Creates a new communication context.
   */
  def context(now : Long, buf1 : ByteBuffer, buf2 : ByteBuffer, buf3 : ByteBuffer) : T =
    new T(
      MessageIO.create(buf1, 10000, buf2, buf3),
      now + pingRequestDelay
    )



  /** Performs a write operation. */
  def doWrite(key : SelectionKey, now : Long, context : T) : Unit = {
    context.ioContext.writeTo(key)
  }



  /** Performs a read operation. */
  def doRead(buf : ByteBuffer ⇒ Unit, key : SelectionKey, now : Long, context : T) : Unit = {
    context.ioContext.updateFrom(key)

    if (context.ioContext.readComplete) {
      key.channel.close()
      return
    }

    while (context.ioContext.inBytes.hasNext) {
      val msg = context.ioContext.inBytes.next

      if (msg.length == 0)
        throw new IOException("Empty message detected")

      msg(0) match {
        case 1 ⇒
          context.nextPingResponse = 0
          context.nextPingRequest = now + pingRequestDelay
        case 0 ⇒
          context.ioContext.outBytes += Array[Byte](1)
        case 2 ⇒
          context.ioContext.outBytes += msg
      }
    }

    doWrite(key, now, context)
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
      context.ioContext.outBytes += Array[Byte](0)
      context.nextPingResponse = now + pingResponceDelay

      doWrite(key, now, context)
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
    context.ioContext.close().foreach(bufferReleaseCallback)
  }
}
