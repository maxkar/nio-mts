package ru.maxkar.cs

import java.nio.channels._
import java.net._

import ru.maxkar.cs.chan.Multiplexor
import ru.maxkar.cs.chan.Handlers

/**
 * Main application launcher.
 */
final object App {
  def main(args : Array[String]) : Unit = {
    val port = 3311

    val serverChannel = ServerSocketChannel.open()
    serverChannel.configureBlocking(false)
    serverChannel.bind(
      new InetSocketAddress(
        InetAddress.getLoopbackAddress(), port),
      10)

    val multiplex = Multiplexor(200, 15000)

    multiplex.submit((selector, time) ⇒
      serverChannel.register(
        selector,
        SelectionKey.OP_ACCEPT,
        Handlers.acceptor(
          (chan, sel, time) ⇒ {
            chan.close()
          },
          (chan, exn) ⇒ {
            exn.printStackTrace()
            chan.close()
            multiplex.close()
          })))

    System.in.read()
    multiplex.close()
    multiplex.awaitTermination()
    System.out.println("Done!")
  }
}
