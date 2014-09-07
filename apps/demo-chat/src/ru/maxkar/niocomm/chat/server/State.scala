package ru.maxkar.niocomm.chat.server

import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey

/**
 * General interface for the NIO state.
 */
trait State {
  /** Checks if this socket is done. Useful for graceful shutdown. */
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
