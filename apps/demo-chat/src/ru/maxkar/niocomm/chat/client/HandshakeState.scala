package ru.maxkar.niocomm.chat.client

import java.io.IOException
import java.nio.channels.SelectionKey

import ru.maxkar.niocomm.MessageIO
import ru.maxkar.niocomm.Source

/** State during chat handshake. */
private final class HandshakeState(
      context : MessageIO,
      queue : Source[String],
      deathTime : Long)
    extends State {


  override def handleIO(key : SelectionKey, now : Long) : Unit = {
    if (now > deathTime)
      throw new IOException("Handshake timeout")
    context.updateFrom(key)

    if (!context.inData.hasNext) {
      context.writeTo(key)
      return
    }

    val data = context.inData.next

    var count = data.readInt()
    while (count > 0) {
      count -= 1
      println("= " + data.readUTF())
    }

    println("==============================")

    val newState = ConnectedState(context, queue, now)
    key.attach(newState)
    newState.handleIO(key, now)
  }
}



/** Handshake state factory. */
private[client] final object HandshakeState {
  private val handshakeTimeout = 10000

  /** Creates a new state. */
  def apply(name : String, queue : Source[String], context : MessageIO, now : Long) : State = {
    context.outData += (daos ⇒ daos.writeUTF(name))
    new HandshakeState(context, queue, now + handshakeTimeout)
  }
}
