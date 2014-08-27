package ru.maxkar.niocomm.server

import java.nio.ByteBuffer
import java.nio.channels.SelectionKey


/** Server state definition. */
private[server] trait State {
  /** Handles a selection event. */
  def onSelection(key : SelectionKey, now : Long) : Unit



  /** Handles a ping event. */
  def onPing(key : SelectionKey, now : Long) : Unit



  /**
   * Closes this selection key. Returns buffers
   * associated with this state.
   */
  def close(key : SelectionKey) : Seq[ByteBuffer]
}
