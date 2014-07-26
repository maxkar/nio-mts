package ru.maxkar.cs.chan

import java.nio.channels._

/**
 * Handler for the channel IO.
 * Handles a generic top-level selection.
 */
trait SelectionHandler {
  /**
   * Handles an accept operation.
   * @param item item to handle
   * @param timestamp current time.
   */
  def accept(item : SelectionKey, timestamp : Long) : Unit

  /**
   * Handles a connect operation.
   * @param item item to handle.
   * @param timestamp current time.
   */
  def connected(item : SelectionKey, timestamp : Long) : Unit

  /**
   * Handles a read operation (channel have some data).
   * @param item item to handle.
   * @param timestamp current time.
   */
  def read(item : SelectionKey, timestamp : Long) : Unit

  /**
   * Handles a write operation.
   * @param item item to handle.
   * @param timestamp current time.
   */
  def write(item : SelectionKey, timestamp : Long) : Unit


  /**
   * Pings channel if needed. This method is invoked periodically
   * by the multiplexor. Good implementation should regullary monitor
   * any channel problems (channel canont proceed) and handle them
   * gracefully. For keep-alive channels implementations should send
   * keep-alive packets.
   * <p>This method must not modify key set of the selector.
   * @param item item to handle.
   * @param timestamp current time.
   */
  def ping(item : SelectionKey, timestamp : Long) : Unit


  /**
   * Handles a "demultiplexing" event. This means that selector
   * is closing or handler is removing from the selector.
   */
  def demultiplex(item : SelectionKey) : Unit
}
