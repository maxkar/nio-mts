package ru.maxkar.cs.chan

import java.nio.channels._


/**
 * Communication multiplexor. Performs all the communications
 * in a single-threaded manner.
 * @param C type of the context. All attachments should be
 *  compatible with this type.
 * @param handler input/output handler.
 * @param commandBacklog number of command available to
 *   leave unprocessed.
 * @param commandBatchSize number of commands to
 *   process in one iteration.
 * @param pingTimeoutMs timeout between handler pings,
 *   in millis
 * @param threadFactory factory for thread creation.
 */
final class Multiplexor[C] private(
    handler : IOHandler[C],
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
   * it should register an instance of C as an attachment.
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
   * @returns <code>true</code> if it is a first call to close/closeBy and
   *  <code>false</code> otherwise.
   */
  def close() : Boolean = closeBy(Multiplexor.closeAll)


  /**
   * Closes a multiplexor by invoking a killer on the seletor.
   * In general, this selector should close all the channels
   * or move them to other selector (or queue). This method does
   * not wait for a complete termination.
   * See SelectorUtil for handfull methods.
   * @return <code>true</code> if this is a first close
   *  request and passed closer will be applied to the selector.
   *  Returns <code>false</code> iff this multiplexor is
   *  closing or closed and this closer will be ignored.
   */
  def closeBy(closer : (Selector, Long) ⇒ Unit) : Boolean =
    queue.putKiller(closer)



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
      /* It is open in a case of (fatal) exception.
       * Try to gracefully close all remaining connections.
       */
      if (selector.isOpen())
        SelectorUtil.safeAbortBy(selector, key ⇒
          if (key.isValid())
            key.channel().close())
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
      doOp(iter.next(), now, handler.onPing)
  }


  /** Handles one I/O operation. */
  private def handleOneIO(key : SelectionKey, now : Long) : Unit = {
    if (key.isValid() && key.isAcceptable())
      doOp(key, now, handler.onAccept)
    if (key.isValid() && key.isConnectable())
      doOp(key, now, handler.onConnect)
    if (key.isValid() && key.isReadable())
      doOp(key, now, handler.onRead)
    if (key.isValid() && key.isWritable())
      doOp(key, now, handler.onWrite)
  }

  /*
   * Performs one operation. Calls an error handler in a case
   * of the exception.
   */
  private def doOp(
        key : SelectionKey, now : Long,
        op : (SelectionKey, Long, C) ⇒ Unit)
      : Unit =
    try {
      op(key, now, key.attachment().asInstanceOf[C])
    } catch {
      case t : Throwable ⇒
        try {
          handler.onError(key, now, key.attachment().asInstanceOf[C], t)
        } catch {
          case t : Throwable ⇒
            t.printStackTrace()
            try {
              key.channel().close()
            } catch {
              case t : java.io.IOException ⇒ t.printStackTrace()
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
  import SelectorUtil._



  /** Temporary handler. */
  @deprecated
  private val compatHandler = new IOHandler[SelectionHandler](
    onAccept = (k, t, c) ⇒ c.accept(k, t),
    onConnect = (k, t, c) ⇒ c.connected(k, t),
    onRead = (k, t, c) ⇒ c.read(k, t),
    onWrite = (k, t, c) ⇒ c.write(k, t),
    onPing = (k, t, c) ⇒ c.ping(k, t),
    onError =
     (k, t, c, err) ⇒ {
       try {
         err.printStackTrace()
       } finally {
         c.demultiplex(k)
       }
     }
  )



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
      : Multiplexor[SelectionHandler] = {
    val result = new Multiplexor(
      compatHandler,
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



  /**
   * This handler will print stack trace of the exception
   * and will close an associated channel.
   */
  def printStackAndClose(
        key : SelectionKey, t : Long, c : Any, err : Throwable)
      : Unit =
    try {
      err.printStackTrace()
    } finally {
      key.channel().close()
    }



  /** This handler will try to close a channel silently
   * (completely ignoring an exception).
   */
  def closeSilently(
        key : SelectionKey, t : Long, c : Any, err : Throwable)
      : Unit =
    key.channel().close()



  /** Aborts the processing by closing failed channel,
   * all other channels associated with <code>key</code>'s
   * selector and selector itself.
   * Attempts to print source throwable and all throwables
   * during the process.
   */
  def printStackAndAbort(
        key : SelectionKey, t : Long, c : Any, err : Throwable)
      : Unit =
    try {
      err.printStackTrace()
    } finally {
      safeAbortBy(key.selector(), key ⇒ key.channel().close())
    }



  /** Aborts processing by closing failed channels,
   * all other channels associated wich <code>key</code>'s
   * selector and selector itself.
   * Silently ignores both <code>err</code> and exceptions
   * happening during calls to close.
   */
  def abortSilently(
        key : SelectionKey, t : Long, c : Any, err : Throwable)
      : Unit =
    unsafeAbortBy(key.selector(), item ⇒
      try {
        item.channel().close()
      } finally {
      })



  /** Default close handler which closes all the associated channels
   * and selector itself.
   */
  def closeAll(selector : Selector, now : Long) : Unit =
    safeAbortBy(selector, item ⇒ item.channel().close())
}
