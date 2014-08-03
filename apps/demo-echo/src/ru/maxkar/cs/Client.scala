package ru.maxkar.cs

import java.io._
import java.nio._
import java.nio.channels._
import java.net._

import ru.maxkar.cs.chan._
import ru.maxkar.cs.msg._

/**
 * Echo client.
 */
final object Client {
  private class ClientContext(val handler : IOHandler[ClientContext])

  def afterConnect(multiplex : Multiplexor[_],
        key : SelectionKey, chan : SocketChannel, now : Long, context : ClientContext) : Unit = {
    val readBuf = ByteBuffer.allocateDirect(1024)
    val write1 = ByteBuffer.allocateDirect(1024)
    val write2 = ByteBuffer.allocateDirect(1024)

    val writer = new MessageWriter(write1, write2, key)
    val pinger = new Pinger(10000, 30000, writer.ping)
    val reader = new MessageReader(readBuf,
      msg ⇒
        if (msg == null) {
          chan.close()
          key.selector().close()
          System.exit(0)
        } else
          System.out.println(new String(msg, "UTF-8"))
    , writer.pingReply, pinger.resetPing, 10000)

    key.interestOps(SelectionKey.OP_READ)
    key.attach(new ClientContext(IOHandler.communicationHandler(
      onRead = (k, n, c) ⇒ reader.doRead(k, n),
      onWrite = (k, n, c) ⇒ writer.doWrite(k, n),
      onPing = (k, n, c) ⇒ pinger.doPing(k, n),
      onError = Multiplexor.printStackAndAbort)))


    new Thread(new Runnable() {
      def run() : Unit = {
        println("Ready")
        val reader = new BufferedReader(new InputStreamReader(System.in, "UTF-8"))

        while (true) {
          val line = reader.readLine()
          if (line == null) {
            chan.shutdownOutput()
            println("Bye!")
            return
          }
          multiplex.submit((selector, time) ⇒
            writer.send(line.getBytes("UTF-8")))
        }
      }
    }).start()
  }


  def work(server : InetSocketAddress) : Unit = {
    val chan = SocketChannel.open()
    chan.configureBlocking(false)
    chan.connect(server)

    val multiplex = Multiplexor.make(
      IOHandler.polymorph[ClientContext](c ⇒ c.handler),
      200, 2000)

    multiplex.submit((sel, now) ⇒
      chan.register(sel, SelectionKey.OP_CONNECT, new ClientContext(
        IOHandler.autoConnector(
          afterConnect = afterConnect(multiplex, _, _, _, _),
          onError = Multiplexor.printStackAndAbort,
          connectDeadline = now + 5000))))

    multiplex.awaitTermination()
  }
}
