package ru.maxkar.cs.chan

import java.nio.channels._


/**
 * Communication multiplexor. Performs all the communications
 * in a single-threaded manner.
 * @param commandBacklog number of command available to
 *   leave unprocessed.
 * @param commandBatchSize number of commands to
 *   process in one iteration.
 * @param threadFactory factory for thread creation.
 */
final class Multiplexor private(
    commandBacklog : Int,
    commandBatchSize : Int,
    threadFactory : Runnable ⇒ Thread) {


  /* Command queue. */
  private var queue = new CommandQueue(commandBacklog, wakeup)


  /* Worker thread for this multiplexor. */
  private val worker = threadFactory(new Runnable() {
    override def run() : Unit = handle()
  })


  /** Active selector. */
  private val selector = Selector.open()


  /**
   * Awaits termination of this multiplexor. Usefull for waiting after closing this
   * multiplexor, but can be called from any place.
   * @param timeout number of milliseconds to wait.
   *  Value 0 means "wait indefinitely".
   * @throws InterruptedException if waiting was interrupted.
   */
  def awaitTermination(timeout : Int = 0) : Unit =
    worker.join(timeout)


  /**
   * Closes this multiplexor but does not wait for termination.
   */
  def close() : Unit = queue.putKiller(doClose)



  /* Handles an actual communication. */
  private def handle() : Unit = {
    try {
      while (selector.isOpen()) {
        /* Wait for the commands and/or IO. */
        selector.select()

        handleIO()

        /** Process commands. If there are possible commands remaining,
         * execute pending IO and return to commands.
         */
        while (handleCommands()) {
          /* We processed a "close" command or something else killed this selector. */
          if (!selector.isOpen())
            return

          if (selector.selectNow() > 0)
            handleIO()
        }
      }
    } finally {
      /* It is open in a case of exception. */
      if (selector.isOpen())
        doClose(selector)
    }
  }


  /** Handles an IO part of the selection.
   * Works with a ready items.
   */
  private def handleIO() : Unit = {
  }


  /*
   * Handles Commands. Returns true if there can be unhandled commands.
   */
  private def handleCommands() : Boolean = {
    var remainingAttempts = commandBatchSize
    while (remainingAttempts > 0) {
      val nextCommand = queue.getCommand()
      if (nextCommand == null)
        return false

      try {
        nextCommand(selector)
      } catch {
        case t : Throwable ⇒
          /* Let's be a little bit paranoid about handlers. */
          t.printStackTrace()
      }
      remainingAttempts -= 1
    }
    true
  }

  /* Performs an actual closing on the selector. */
  private def doClose(selector : Selector) : Unit = {
  }


  /** Wakeups this item from sleeping. */
  private def wakeup() : Unit = selector.wakeup()
}





/** Multiplexor companion. */
object Multiplexor {
  /** Creates a new multiplexor.
   * @param commandBacklog number of commands allowed in the queue.
   * @param commandBatchSize number of commands to process before
   *   dropping to IO. If zero, then commandBacklog will be used
   *   as a batch size.
   * @param threadFactory daemon thread factory. If null,
   *   then default value will be used.
   */
  def apply(
        commandBacklog : Int,
        commandBatchSize : Int = 0,
        threadFactory : Runnable ⇒ Thread = null)
      : Multiplexor = {
    val result = new Multiplexor(
      commandBacklog,
      if (commandBatchSize <= 0) commandBacklog else commandBatchSize,
      if (threadFactory == null) defaultThreadFactory else threadFactory)
    result.worker.start()
    result
  }



  /** Default thread factory. */
  def defaultThreadFactory(r : Runnable) : Thread = {
    val res = new Thread(r)
    res.setName("IO Multiplexor thread")
    res
  }
}
