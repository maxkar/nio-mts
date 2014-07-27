package ru.maxkar.cs.msg


import java.nio.channels._
import java.io.IOException

/**
 * Ping handler.
 * @param timeout ping timeout in millis
 * @param interval ping interval
 * @param requestor ping request function.
 */
private [msg] final class Pinger(
      timeout : Int,
      interval : Int,
      requestor : () ⇒ Unit) {

  /** Time when a next ping is expected. */
  var replyExpected : Long = 0

  /** Next ping time. */
  var nextPing : Long = 0

  def doPing(key : SelectionKey, now : Long) : Unit = {
    if (nextPing > now) {
      return
    }

    if (replyExpected == 0) {
      replyExpected = now + timeout
      requestor()
      return
    }

    if (replyExpected < now)
      throw new IOException("Ping timed out")
  }

  /* Resets a ping status. */
  def resetPing(now : Long) : Unit = {
    replyExpected = 0
    nextPing = now + interval
  }
}
