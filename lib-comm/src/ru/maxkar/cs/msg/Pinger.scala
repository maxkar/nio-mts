package ru.maxkar.cs.msg


import java.nio.channels._
import java.io.IOException

/**
 * Ping handler.
 * @param C type of the context.
 */
final class Pinger[-C] private(
      extractor : C ⇒ Pinger.Context,
      timeout : Int,
      interval : Int,
      requestor : (SelectionKey, Long, C) ⇒ Unit) {


  /** Handles a ping stimulus. Requests a ping if it is
   * a good time. Throws an IOException if ping was not
   * reset after last stimulus was sent.
   */
  def doPing(key : SelectionKey, now : Long, context : C) : Unit = {
    val c = extractor(context)

    if (c.nextPing > now) {
      return
    }

    if (c.replyExpected == 0) {
      c.replyExpected = now + timeout
      requestor(key, now, context)
      return
    }

    if (c.replyExpected < now)
      throw new IOException("Ping timed out")
  }

  /* Resets a ping status. */
  def reset(key : SelectionKey, now : Long, context : C) : Unit = {
    val c = extractor(context)
    c.replyExpected = 0
    c.nextPing = now + interval
  }
}


/**
 * Ping handler.
 */
final object Pinger {
  /** Ping context. */
  final class Context private[Pinger]() {
    /** Time at which reply is expected. */
    private[Pinger] var replyExpected : Long = 0

    /** Time of a next ping. */
    private[Pinger] var nextPing : Long = 0
  }


  /**
   * Creates a new pinger (ping handler).
   * @param C type of the context.
   * @param extractor context extractor.
   * @param timeout ping timeout.
   * @param intervar time between two pings.
   * @param requestor ping communication requestor.
   */
  def pinger[C](
        extractor : C ⇒ Context,
        timeout : Int,
        interval : Int,
        requestor : (SelectionKey, Long, C) ⇒ Unit)
      : Pinger[C] =
    new Pinger(extractor, timeout, interval, requestor)

  /**
   * Creates a new ping context.
   */
  def context() : Context = new Context()
}
