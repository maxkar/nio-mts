package ru.maxkar.niocomm.chat.server

import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.Selector
import java.nio.channels.SelectionKey
import java.nio.channels.ServerSocketChannel

/**
 * Server implementation class.
 */
private final class Server(socket : ServerSocketChannel) {
  /** Chat for this server. */
  private val chat = new Chat()



  /** Buffer pool. */
  private val buffers = new BufferPool(1024 * 1024)



  /** Selector associated with this server. */
  private val selector = Selector.open()



  /** Handles a chat communication. */
  private def handle() : Unit = {
    var now = System.currentTimeMillis()
    var nextPing = now + Server.pingInterval

    socket.register(selector, SelectionKey.OP_ACCEPT,
      new AcceptState(buffers.allocate, chat))

    while (socket.isOpen()) {
      val sleepTime = Math.max(100, nextPing - now)
      selector.select(sleepTime)
      now = System.currentTimeMillis()

      updateSelected(now)

      if (now > nextPing) {
        nextPing = now + Server.pingInterval
        pingAll(now)
      }

      flushChat(now)
    }

    val itr = selector.keys().iterator
    while (itr.hasNext())
      try {
        itr.next().channel().close()
      } catch {
        case e : IOException ⇒ e.printStackTrace()
      }
    selector.close()
  }



  /** Flushes a global chat queues. */
  private def flushChat(now : Long) : Unit = {
    while (chat.dirty()) {
      /* Flush messages. */
      val itr = chat.activeKeys.iterator()
      while (itr.hasNext())
        doSafe(doSelect, itr.next(), now)


      /** Remove all failed clients. */
      while (chat.failedKeys.hasNext)
        closeKey(chat.failedKeys.next())
    }
  }



  /** Pings all the selectors. */
  private def pingAll(now : Long) : Unit = {
    val itr = selector.keys().iterator()

    while (itr.hasNext()) {
      val key = itr.next()
      if (key.isValid())
        doSafe(doPing, key, now)
    }
  }



  /** Updates selectors based on the selection. */
  private def updateSelected(now : Long) : Unit = {
    val itr = selector.selectedKeys().iterator()

    while (itr.hasNext()) {
      val key = itr.next()
      itr.remove()
      doSafe(doSelect, key, now)
    }
  }



  /** Performs a ping. */
  private def doPing(state : State, key : SelectionKey, now : Long) : Unit =
    state.onPing(key, now)



  /** Updates one state for IO. */
  private def doSelect(state : State, key : SelectionKey, now : Long) : Unit = {
    val newState = state.onSelection(key, now)
    if (newState.isDone())
      closeKey(key)
    else if (state `ne` newState)
      key.attach(newState)
  }



  /** Pefrorms an operation in a safe manner. */
  private def doSafe(
        op : (State, SelectionKey, Long) ⇒ Unit,
        key : SelectionKey,
        now : Long)
      : Unit = {
    val state = key.attachment().asInstanceOf[State]
    try {
      op(state, key, now)
    } catch {
      case e : IOException ⇒
        e.printStackTrace()
        closeKey(key)
    }
  }



  /** Closes a key. */
  private def closeKey(key : SelectionKey) : Unit = {
    val state = key.attachment().asInstanceOf[State]
    try {
      key.channel().close()
    } catch {
      case e1 : IOException ⇒ e1.printStackTrace()
    } finally {
      buffers ++= state.close()
      chat.remove(key)
    }
  }
}



/** Server side API. */
final object Server {
  /** Ping timeout. */
  private val pingInterval = 5000



  /** Serves a chat. */
  def serve(address : InetSocketAddress) : Unit = {
    val sock = ServerSocketChannel.open()
    sock.configureBlocking(false)
    sock.bind(address)

    new Server(sock).handle()
  }
}
