package ru.maxkar.niocomm.client


import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.nio.channels.SocketChannel
import java.nio.channels.Selector
import java.nio.channels.SelectionKey
import scala.collection.mutable.Queue


/** Client handler. */
private final class Client(channel : SocketChannel) {
  /** Outgoing messages. */
  private val messages = new Queue[String]



  /** Used selector. */
  private val selector = Selector.open()


  /**
   * Performs "read" operation.
   */
  private[client] def doRead() : Unit = {
    val br = new BufferedReader(new InputStreamReader(System.in, "UTF-8"))

    while (true) {
      val line = br.readLine
      if (line == null) {
        channel.close()
        selector.wakeup()
        return
      }


      messages synchronized {
        val shouldAwake = messages.isEmpty
        messages += line

        if (shouldAwake)
          selector.wakeup()
      }
    }
  }



  /** Handles all IO.  */
  private[client] def handleSocket() : Unit = {
    try {
      val key = channel.register(selector, SelectionKey.OP_CONNECT,
        ConnectingState.create(messages, System.currentTimeMillis() + 5000))

      while (channel.isOpen()) {
        selector.select(3000)

        val now = System.currentTimeMillis()
        val state = key.attachment().asInstanceOf[State]

        if (key.isValid())
          state.onSelection(key, now)
      }
    } finally {
      channel.close()
      selector.close()
    }
  }
}



/** Client handler. */
final object Client {
  def doMain(address : InetSocketAddress) : Unit = {
    val sock = SocketChannel.open()
    sock.configureBlocking(false)
    sock.connect(address)


    val cs = new Client(sock)

    new Thread(new Runnable() {
      override def run() : Unit = cs.doRead()
    }).start()

    cs.handleSocket()
  }
}
