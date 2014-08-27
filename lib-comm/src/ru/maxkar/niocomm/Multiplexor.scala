package ru.maxkar.niocomm


import java.nio.channels._

/**
 * Communication multiplexor. Performs all operations
 * on one thread.
 * @param handler Input/output handler.
 * @param pinger ping handler.
 * @param commandBacklog number of command available to
 *   leave unprocessed.
 * @param commandBatchSize number of commands to
 *   process in one iteration.
 * @param pingTimeoutMs timeout between handler pings,
 *   in millis
 * @param threadFactory factory for thread creation.
 */
final class Multiplexor private(
    handler : (SelectionKey, Long) ⇒ Unit,
    pinger : (SelectionKey, Long) ⇒ Unit,
    commandBacklog : Int,
    commandBatchSize : Int,
    pingTimeoutMs : Int,
    threadFactory : Runnable ⇒ Thread
  ){


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
   * nicely to the selector.
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
   * Closes a multiplexor by invoking a killer on the seletor.
   * In general, this selector should close all the channels
   * or move them to other selector (or queue). This method does
   * not wait for a complete termination.
   * @return <code>true</code> if this is a first close
   *  request and passed closer will be applied to the selector.
   *  Returns <code>false</code> iff this multiplexor is
   *  closing or closed and this closer will be ignored.
   */
  def close(closer : (Selector, Long) ⇒ Unit) : Boolean =
    queue.putKiller((sel, now) ⇒ {
      try {
        closer(sel, now)
      } finally {
        if (sel.isOpen())
          sel.close()
      }
    })



  /* Handles an actual communication. */
  private def handle() : Unit = {
    var now = System.currentTimeMillis()

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
  }


  /** Handles an IO part of the selection.
   * Works with a ready items.
   */
  private def handleIO(now : Long) : Unit = {
    val iter = selector.selectedKeys().iterator()

    while (iter.hasNext()) {
      val item = iter.next()
      iter.remove()
      if (item.isValid())
        handler(item, now)
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
      pinger(iter.next(), now)
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
   * @param handler Input/output handler.
   * @param pinger ping handler.
   * @param commandBacklog number of command available to
   *   leave unprocessed.
   * @param pingTimeoutMs timeout between handler pings,
   *   in millis
   * @param commandBatchSize number of commands to process before
   *   dropping to IO. If zero, then commandBacklog will be used
   *   as a batch size.
   * @param threadFactory daemon thread factory. If null,
   *   then default value will be used.
   */
  def create(
        handler : (SelectionKey, Long) ⇒ Unit,
        pinger : (SelectionKey, Long) ⇒ Unit,
        commandBacklog : Int,
        pingTimeoutMs : Int,
        commandBatchSize : Int = 0,
        threadFactory : Runnable ⇒ Thread = null)
      : Multiplexor = {
    val result = new Multiplexor(
      handler,
      pinger,
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
