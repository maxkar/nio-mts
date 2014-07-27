package ru.maxkar.cs

import java.nio.channels._
import java.nio._
import java.io._
import java.net._

import ru.maxkar.cs.chan.Multiplexor
import ru.maxkar.cs.chan.Handlers
import ru.maxkar.cs.msg.Messenger
import ru.maxkar.cs.util.BufferPool

/**
 * Main application launcher.
 */
final object App {
  val port = 3311

  def main(args : Array[String]) : Unit = {
    if (args.length > 0)
      client()
    else
      serve()
  }

  private def serverAddress() : InetSocketAddress =
    new InetSocketAddress(
      InetAddress.getLoopbackAddress(), port)


  private def client() : Unit = {
    val chan = SocketChannel.open()
    chan.configureBlocking(false)

    val multiplex = Multiplexor(200, 2000)

    def onConnect(chan : SocketChannel, key : SelectionKey, now : Long) : Unit = {
      val msg = Messenger.bind(
        key,
        ByteBuffer.allocateDirect(1024),
        ByteBuffer.allocateDirect(1024),
        ByteBuffer.allocateDirect(1024),
        (messenger, message) ⇒ println(new String(message, "UTF-8")))

      new Thread(new Runnable() {
        def run() : Unit = {
          println("Ready")
          val reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"))

          while (true) {
            val line = reader.readLine()
            if (line == null) {
              msg.close()
              multiplex.close()
              multiplex.awaitTermination()
              println("Bye!")
              return
            }
            multiplex.submit((selector, time) ⇒
              msg.send(line.getBytes("UTF-8")))
          }
        }
      }).start()
    }

    multiplex.submit((selector, time) ⇒ {
      if (chan.connect(serverAddress()))
        onConnect(chan, chan.register(selector, 0, null), time)
      else {
        chan.register(selector, SelectionKey.OP_CONNECT,
          Handlers.connector(time + 2000, onConnect, (chan, exn) ⇒ {
            exn.printStackTrace()
            multiplex.close()
          }))
      }
    })
  }


  private def serve() : Unit = {
    val serverChannel = ServerSocketChannel.open()
    serverChannel.configureBlocking(false)
    serverChannel.bind(serverAddress(), 10)

    val multiplex = Multiplexor(200, 2000)

    val pool = new BufferPool(1024 * 1024)

    multiplex.submit((selector, time) ⇒
      serverChannel.register(
        selector,
        SelectionKey.OP_ACCEPT,
        Handlers.acceptor(
          (chan, sel, time) ⇒ {
            chan.configureBlocking(false)
            val key = chan.register(sel, 0, null)
            val (b1, b2, b3, closer) = pool.get()

            Messenger.bind(key, b1, b2, b3,
              (messenger, message) ⇒ {
                if (message == null)
                  messenger.close()
                else
                  messenger.send(message)
              },
              closeHandler = closer)
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
