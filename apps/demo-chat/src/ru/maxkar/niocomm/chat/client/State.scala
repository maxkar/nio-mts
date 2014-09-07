package ru.maxkar.niocomm.chat.client

import java.nio.channels.SelectionKey

/**
 * Client communication handler state.
 */
private[client] trait State {
  /** Handles a communication event. */
  def handleIO(key : SelectionKey, now : Long) : Unit
}
