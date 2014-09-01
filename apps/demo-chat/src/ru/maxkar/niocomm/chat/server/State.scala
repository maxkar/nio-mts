package ru.maxkar.niocomm.chat.server

import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey

import ru.maxkar.niocomm.Source
import ru.maxkar.niocomm.Sink

/**
 * General interface for the NIO state.
 */
trait State {

  /**
   * Message source. Sequence of received messages from the client.
   * Each message have a client ID and message payload.
   * In default implementation this queue is always empty.
   */
  val incomingMessages : Source[(String, String)] = Source.empty



  /**
   * Sink for outgoing messages. Each state can receive some
   * messages (and may ignore them if it is not appropriate yet).
   * Default implementation ignores all messages.
   */
  val outgoingMessages : Sink[DataOutputStream ⇒ Unit] = Sink.ignore



  /**
   * Checks if this state is done and should be closed
   * along with the selector. Usually only final (graceful
   * close) states should override this to return true.
   * Default implementation returns false.
   * @return <code>true</code> iff communication reached its logical
   * end and state and channel should be closed.
   */
  def isDone() : Boolean = false



  /**
   * Updates this state after the selection event.
   * @return new handling state (if appropriate).
   */
  def onSelection(key : SelectionKey, now : Long) : State



  /**
   * Hadnles a chat-wide ping event.
   */
  def onPing(key : SelectionKey, now : Long) : Unit



  /**
   * Closes this state.
   * @return list of buffers freed from this state.
   */
  def close() : Seq[ByteBuffer]
}
