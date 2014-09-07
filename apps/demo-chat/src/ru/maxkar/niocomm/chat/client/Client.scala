package ru.maxkar.niocomm.chat.client


import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.channels.Selector
import java.nio.channels.SelectionKey
import java.nio.channels.SocketChannel

import ru.maxkar.niocomm.Source


/** Chat client. */
private final class Client(socket : SocketChannel, name : String) {


  /** Active selector. */
  private val selector = Selector.open()


  /** Message queue. */
  private val queue = new scala.collection.mutable.Queue[String]



  /** Message source. */
  private val source = Source[String](
    () ⇒ queue synchronized !queue.isEmpty,
    () ⇒ queue synchronized queue.dequeue())



  /** Performs all the operations. */
  private def handle() : Unit = {
    try {
      val key = socket.register(selector, SelectionKey.OP_CONNECT,
        ConnectingState(name, source, System.currentTimeMillis()))

      while(socket.isOpen()) {
        selector.select(10000)
        selector.selectedKeys().clear()
        key.attachment().asInstanceOf[State].handleIO(key, System.currentTimeMillis())
      }
    } catch {
      case e : IOException ⇒ e.printStackTrace()
    } finally {
      try {
        socket.close()
      } finally {
        selector.close()
      }
    }
  }
}



/** Chat client implementation. */
final object Client {
  def handle(name : String, address : InetSocketAddress) : Unit = {
    val sock = SocketChannel.open()
    sock.configureBlocking(false)
    sock.connect(address)

    val client = new Client(sock, name)

    val inCopier = new Thread(new Runnable {
      override def run() : Unit = {
        val is = new BufferedReader(new InputStreamReader(System.in, "UTF-8"))

        var msg : String = null

        do {
          msg = is.readLine()
          client.queue synchronized { client.queue += msg }
          client.selector.wakeup()
        } while (msg != null)
      }
    })
    inCopier.setDaemon(true)
    inCopier.start()

    client.handle()
  }
}
