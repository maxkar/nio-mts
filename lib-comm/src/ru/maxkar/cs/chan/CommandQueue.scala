package ru.maxkar.cs.chan

import ru.maxkar.cs.InsufficientResourcesError
import java.io.IOException

import java.nio.channels.Selector

/**
 * Commands queue. Do not support nulls.
 * @param backlog number of allowed items in the queue.
 * @param notifier function to call when queue is not empty.
 * @param T type of stored objects.
 */
private [chan] class CommandQueue(backlog : Int, notifier : () ⇒ Unit) {

  /* Element type. */
  type T = (Selector, Long) ⇒ Unit

  if (backlog > Integer.MAX_VALUE / 2 - 1)
    throw new InsufficientResourcesError(backlog + " is too much for the backlog")

  if (backlog <= 0)
    throw new IllegalArgumentException(backlog + " is too few for backlog")


  /** Command store. */
  private val store = new Array[T](backlog * 2 + 1)


  /** Global application lock. */
  private val lock = new Object()


  /** Last item index. */
  private[chan] val last = store.length - 1


  /* WRITERS. */

  /** Write pointer. */
  private var writePtr = 0

  /** Number of items marked as "thread-unsafe" for the reader. */
  private var unsafeSize = 0

  /** EOF flag. */
  private var eof = false


  /** READER/WRITER COMMUNICATION */

  /** Flag, indicating that multiplexor shoulde be "wakened" when
   * next item is put into the queue. Reset upon notification.
   * Armed by read attempt observing no values in the queue. */
  private var needNotify = true


  /* READER.
   * Reader items are safe to access from the handler thread only.
   */

  /** Read pointer.
   * ONLY SINGLE-THEADED ACCESS.
   */
  private var readPtr = 0

  /** Number of items available without the synchronization.
   * SINGLE-THREADED ACCESS.*/
  private var safeSize = 0




  /* WRITE METHODS. */

  /** Puts an item into the queue. */
  private[chan] def put(item : T) : Unit =
    lock synchronized {
      if (eof)
        throw new IOException("Queue is already closed. Use a new queue.")
      if (unsafeSize == backlog)
        throw new InsufficientResourcesError("Queue is full. Came back later.")

      enqueue(item)
    }


  /** Puts a "killer" handler. Queue will
   * enqueue a first used killer. Other killers will be
   * ignored. Other items will not be put into this queue.
   * There is always enough space for a killer.
   * @return <code>true</code> if killer was put or
   *  <code>false</code> if this queue already have a killer.
   */
  private[chan] def putKiller(item : T) : Boolean =
    lock synchronized {
      if (eof)
        return false

      /* backlog in processing + backlog in queue + this killer =
       * acutal queue capacity.
       */
      enqueue(item)
      eof = true
      true
    }


  /* Enqueues an item. This method should be called while holding
   * a lock. */
  private def enqueue(item : T) : Unit = {
    if (item == null)
      throw new IllegalArgumentException("Cannot enqueue null")

    unsafeSize += 1
    store(writePtr) = item
    if (writePtr == last)
      writePtr == 0
    else
      writePtr += 1

    /* Call a handler if it is waiting for this. */
    if (needNotify) {
      needNotify = false
      notifier()
    }
  }




  /* READ METHODS. */

  /**
   * Reads a next command. If there is no commands,
   * returns <code>null</code>.
   */
  private[chan] def getCommand() : T = {
    /* If can read safely then do it and don't synchronize. */
    if (safeSize > 0)
      return extractSafeCommandFromQueue()

    /* Move all items to a safe area. */
    lock synchronized {
      safeSize = unsafeSize
      unsafeSize = 0

      /* Observing an empty queue, can sleep so need to be awakened. */
      if (safeSize == 0)
        needNotify = true
    }


    /* If got some items to read - read it */
    if (safeSize > 0)
      extractSafeCommandFromQueue()
    else
      null
  }


  /** Extracts a "safe" value from the queue.
   */
  private def extractSafeCommandFromQueue() : T = {
    safeSize -= 1
    val result = store(readPtr)
    /* Do not retain an object. */
    store(readPtr) = null
    if (readPtr == last)
      readPtr = 0
    else
      readPtr += 1
    result
  }
}
