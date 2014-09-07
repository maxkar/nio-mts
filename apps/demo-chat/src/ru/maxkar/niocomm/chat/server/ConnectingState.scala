package ru.maxkar.niocomm.chat.server

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey

import ru.maxkar.niocomm.MessageIO

/**
 * State for the connecting client.
 */
private final class ConnectingState private(
      context : MessageIO,
      chat : Chat,
      connectEnd : Long)
    extends State {


  override def onSelection(key : SelectionKey, now : Long) : State = {
    context.updateFrom(key)

    if (context.readComplete)
      throw new IOException("Unexpected EOF")

    if (!context.inData.hasNext)
      return this

    val name = context.inData.next.readUTF()
    val chatter = new Visitor(name, context)

    chat.add(key, chatter)

    ChattingState(chat, context, name, now).onSelection(key, now)
  }



  override def onPing(key : SelectionKey, now : Long) : Unit =
    if (connectEnd < now)
      throw new IOException("Connection timeout expired")



  override def close() : Seq[ByteBuffer] = context.close()
}



/** Connection state factory. */
private[server] object ConnectingState {
  /** Connection timeout. */
  private val connectTimeout = 10000


  /** Creates new connecting state. */
  def apply(context : MessageIO, chat : Chat, now : Long) : State =
    new ConnectingState(context, chat, now + connectTimeout)
}
