package ru.maxkar.cs.msg

import ru.maxkar.cs.chan.SelectionHandler
import java.nio.channels._

private [msg] final class MessageHandler(
      reader : MessageReader,
      writer : MessageWriter,
      pinger : Pinger,
      closeHandler : () ⇒ Unit)
    extends SelectionHandler {

  def accept(item : SelectionKey, timestamp : Long) : Unit = ()
  def connected(item : SelectionKey, timestamp : Long) : Unit = ()

  def read(item : SelectionKey, timestamp : Long) : Unit =
    try {
      reader.doRead(item, timestamp)
    } catch {
      case t : Throwable ⇒
        demultiplex(item)
        throw t
    }

  def write(item : SelectionKey, timestamp : Long) : Unit =
    try {
      writer.doWrite(item, timestamp)
    } catch {
      case t : Throwable ⇒
        demultiplex(item)
        throw t
    }

  def ping(item : SelectionKey, timestamp : Long) : Unit =
    try {
      pinger.doPing(item, timestamp)
    } catch {
      case t : Throwable ⇒
        demultiplex(item)
        throw t
    }

  def demultiplex(item : SelectionKey) : Unit = {
    closeHandler()
  }
}
