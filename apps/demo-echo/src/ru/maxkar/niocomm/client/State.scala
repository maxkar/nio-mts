package ru.maxkar.niocomm.client

import java.nio.channels.SelectionKey

/** Server state definition. */
private[client] trait State {
  /** Handles a selection event. */
  def onSelection(key : SelectionKey, now : Long) : Unit
}
