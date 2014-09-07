package ru.maxkar.niocomm.chat.server

import java.io.DataOutputStream
import java.io.IOException
import java.nio.channels.SelectionKey

import scala.collection.mutable.Queue

import ru.maxkar.niocomm._

/**
 * Chat room handler.
 */
private[server] final class Chat {
  /**
   * Flag indicating that this chat sent some broadcast
   * messages after last check of the dirty state.
   */
  private var isDirty : Boolean = false



  /** Keys failed during operations on this chat. */
  private val failedKeyQueue = new Queue[SelectionKey]



  /** Mapping from keys to visitors registered in this chat. */
  private val visitors = new java.util.HashMap[SelectionKey, Visitor]



  /** Alive visitors. */
  private val alive = new java.util.HashMap[SelectionKey, Visitor]



  /** Keys with failed IO operations on them. */
  val failedKeys = Source[SelectionKey](
    () ⇒ !failedKeyQueue.isEmpty, failedKeyQueue.dequeue)



  /** Broadcast message sink for this chat. */
  val messages = Sink(sendBroadcast)



  /** Active selection keys. */
  val activeKeys = visitors.keySet()



  /** Sends one broadcast message. */
  private def sendBroadcast(message : DataOutputStream ⇒ Unit) : Unit = {
    if (alive.isEmpty())
      return
    isDirty = true

    val itr = alive.entrySet().iterator()

    while (itr.hasNext()) {
      val entry = itr.next()
      val visitor = entry.getValue()

      try {
        visitor.messages += message
      } catch {
        case e : IOException ⇒
          failedKeyQueue += entry.getKey()
          e.printStackTrace()
      }
    }
  }



  /**
   * Checks if this chat is dirty (have unflushed changes) and rests
   * a status.
   */
  def dirty() : Boolean = {
    val res = isDirty
    isDirty = false
    res
  }



  /** Adds a visitor to this chat.
   * Sends list of visitor to this new visitor.
   */
  def add(key : SelectionKey, visitor : Visitor) : Unit = {
    visitor.messages += (dos ⇒ {
      dos.writeInt(visitors.size())
      val itr = visitors.values.iterator()
      while (itr.hasNext())
        dos.writeUTF(itr.next().name)
    })

    messages += (dos ⇒ {
      dos.writeByte(3)
      dos.writeUTF(visitor.name)
    })

    visitors.put(key, visitor)
    alive.put(key, visitor)
  }



  /**
   * Removes visitor from this chat.
   */
  def remove(key : SelectionKey) : Unit = {
    val visitor = visitors.remove(key)
    if (visitor == null)
      return
    alive.remove(key)
    messages += (dos ⇒ {
      dos.writeByte(4)
      dos.writeUTF(visitor.name)
    })
  }
}
