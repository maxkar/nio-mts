package ru.maxkar.niocomm.chat.client

import java.io.IOException
import java.nio.channels.SelectionKey

import ru.maxkar.niocomm.MessageIO
import ru.maxkar.niocomm.Source

/** State handler for the connected state. */
private final class ConnectedState(
      context : MessageIO,
      queue : Source[String],
      private var nextPingRequest : Long)
    extends State {

  /** Next ping response time. */
  private var nextPingResponse : Long = 0


  /** Handles an IO loop. */
  override def handleIO(key : SelectionKey, now : Long) : Unit = {
    context.updateFrom(key)

    while (context.inData.hasNext()) {
      val data = context.inData.next()
      val head = data.readByte()
      head match {
        case 0 ⇒
          context.outBytes += ConnectedState.pingResponse
        case 1 ⇒
          nextPingRequest = now + ConnectedState.pingDelay
          nextPingResponse = 0
        case 2 ⇒
          println("  " + data.readUTF() + " ⇒ " + data.readUTF())
        case 3 ⇒
          println("+ " + data.readUTF())
        case 4 ⇒
          println("- " + data.readUTF())
        case x ⇒
          throw new IOException("Bad message code " + x)
      }
    }

    if (nextPingResponse > 0) {
      if (nextPingResponse < now)
        throw new IOException("Ping timeout")
    } else if (nextPingRequest < now) {
      context.outBytes += ConnectedState.pingRequest
      nextPingResponse = now + ConnectedState.pingWaitTime
      nextPingRequest = 0
    }


    while (queue.hasNext) {
      val msg = queue.next
      if (msg != null)
        context.outData += (daos ⇒ {
          daos.writeByte(2)
          daos.writeUTF(msg)
        })
      else {
        context.shutdownOutput()
        val ns = ShutdownState(context, now)
        key.attach(ns)
        ns.handleIO(key, now)
        return
      }
    }

    context.writeTo(key)
  }
}



/** Connected state companion. */
private[client] final object ConnectedState {
  /** Ping request message. */
  private val pingRequest = Array[Byte](0)

  /** Ping response message. */
  private val pingResponse = Array[Byte](1)

  /** Delay between pings. */
  private val pingDelay = 30000

  /** Ping wait time. */
  private val pingWaitTime = 30000


  /** Creates a new state. */
  def apply(context : MessageIO, queue : Source[String], now : Long) : State =
    new ConnectedState(context, queue, now + pingDelay)
}
