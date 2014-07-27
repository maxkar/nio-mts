package ru.maxkar.cs.chan

import java.nio.channels._


/**
 * Communication multiplexor. Performs all the communications
 * in a single-threaded manner.
 * @param commandBacklog number of command available to
 *   leave unprocessed.
 * @param commandBatchSize number of commands to
 *   process in one iteration.
 * @param pingTimeoutMs timeout between handler pings,
 *   in millis
 * @param threadFactory factory for thread creation.
 */
final class Multiplexor private(
    commandBacklog : Int,
    commandBatchSize : Int,
    pingTimeoutMs : Int,
    threadFactory : Runnable ⇒ Thread) {


  /* Command queue. */
  private var queue = new CommandQueue(commandBacklog, wakeup)


  /* Worker thread for this multiplexor. */
  private val worker = threadFactory(new Runnable() {
    override def run() : Unit = handle()
  })


  /* Time of next ping. */
  private var nextPing : Long = System.currentTimeMillis() + pingTimeoutMs


  /** Active selector. */
  private val selector = Selector.open()



  /**
   * Submits a command for the execution. Command must behave
   * nicely to the selector. If command adds item to the selector, then
   * it should register SelectionHandler as an attachment.
   * @throws InsufficientResouresException if queue is full.
   * @throws IOException if this multiplexor is closing or closed.
   */
  def submit(command : (Selector, Long) ⇒ Unit) : Unit =
    queue.put(command)


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
    var now = System.currentTimeMillis()
    try {

      while (selector.isOpen()) {
        val waitTime = nextPing - now

        /* Wait for the commands and/or IO. */
        selector.select(Math.max(100, waitTime))

        handleIO(now)

        /** Process commands. If there are possible commands remaining,
         * execute pending IO and return to commands.
         */
        while (handleCommands(now)) {
          /* We processed a "close" command or something else killed this selector. */
          if (!selector.isOpen())
            return

          if (selector.selectNow() > 0) {
            now = System.currentTimeMillis()
            handleIO(now)
          }
        }

        now = System.currentTimeMillis()
      }
    } finally {
      /* It is open in a case of exception. */
      if (selector.isOpen())
        doClose(selector, now)
    }
  }


  /** Handles an IO part of the selection.
   * Works with a ready items.
   */
  private def handleIO(now : Long) : Unit = {
    val iter = selector.selectedKeys().iterator()

    while (iter.hasNext()) {
      val item = iter.next()
      iter.remove()
      handleOneIO(item, now)
    }

    pingIfNeeded(now)
  }


  /** Ping items if needed. */
  private def pingIfNeeded(now : Long) : Unit = {
    if (now < nextPing)
      return
    nextPing = now + pingTimeoutMs

    val iter = selector.keys().iterator()
    while (iter.hasNext())
      pingOne(iter.next(), now)
  }


  /* Performs an actual closing on the selector. */
  private def doClose(selector : Selector, now : Long) : Unit = {
    val iter = selector.keys().iterator()
    while (iter.hasNext())
      demuxOne(iter.next())
    selector.close()
  }


  /** Handles one I/O operation. */
  private def handleOneIO(key : SelectionKey, now : Long) : Unit = {
    try {
      val handler = key.attachment().asInstanceOf[SelectionHandler]

      if (key.isValid() && key.isAcceptable())
        handler.accept(key, now)
      if (key.isValid() && key.isConnectable())
        handler.connected(key, now)
      if (key.isValid() && key.isReadable())
        handler.read(key, now)
      if (key.isValid() && key.isWritable())
        handler.write(key, now)
    } catch {
      case t : Throwable ⇒
        try {
          /* Cancel selection and close the channel. We can't deal
           * with the broken handler. */
          try {
            key.cancel()
          } finally {
            key.channel().close()
          }
          t.printStackTrace()
        } catch {
          case x : Throwable ⇒ x.printStackTrace()
        }
    }
  }


  /**
   * Pings one item.
   */
  private def pingOne(key : SelectionKey, now : Long) : Unit = {
    try {
      val handler = key.attachment().asInstanceOf[SelectionHandler]

      handler.ping(key, now)
    } catch {
      case t : Throwable ⇒
        try {
          /* Cancel selection and close the channel. We can't deal
           * with the broken handler. */
          try {
            key.cancel()
          } finally {
            key.channel().close()
          }
          t.printStackTrace()
        } catch {
          case x : Throwable ⇒ x.printStackTrace()
        }
    }
  }


  /** "Demuxes" one item. */
  private def demuxOne(key : SelectionKey) : Unit = {
    try {
      key.attachment().asInstanceOf[SelectionHandler].demultiplex(key)
    } catch {
      case t : Throwable ⇒
        try {
          /* Cancel selection and close the channel. We can't deal
           * with the broken handler. It is better to close the channel
           * (if handler failed to handle this) otherwise we can end up
           * with the broken channel. */
          try {
            key.cancel()
          } finally {
            key.channel().close()
          }
          t.printStackTrace()
        } catch {
          case x : Throwable ⇒ x.printStackTrace()
        }
    }
  }


  /*
   * Handles Commands. Returns true if there can be unhandled commands.
   */
  private def handleCommands(now : Long) : Boolean = {
    var remainingAttempts = commandBatchSize
    while (remainingAttempts > 0) {
      val nextCommand = queue.getCommand()
      if (nextCommand == null)
        return false

      try {
        nextCommand(selector, now)
      } catch {
        case t : Throwable ⇒
          /* Let's be a little bit paranoid about handlers. */
          t.printStackTrace()
      }
      remainingAttempts -= 1
    }
    true
  }


  /** Wakeups this item from sleeping. */
  private def wakeup() : Unit = selector.wakeup()
}





/** Multiplexor companion. */
object Multiplexor {
  /** Creates a new multiplexor.
   * @param commandBacklog number of commands allowed in the queue.
   * @param pingTimeoutMs ping timeout in millis.
   * @param commandBatchSize number of commands to process before
   *   dropping to IO. If zero, then commandBacklog will be used
   *   as a batch size.
   * @param threadFactory daemon thread factory. If null,
   *   then default value will be used.
   */
  def apply(
        commandBacklog : Int,
        pingTimeoutMs : Int,
        commandBatchSize : Int = 0,
        threadFactory : Runnable ⇒ Thread = null)
      : Multiplexor = {
    val result = new Multiplexor(
      commandBacklog,
      if (commandBatchSize <= 0) commandBacklog else commandBatchSize,
      pingTimeoutMs,
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
