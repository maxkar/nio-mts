package ru.maxkar.cs

import java.io._
import java.net._
import java.nio.channels._

import ru.maxkar.cs.msg._
import ru.maxkar.cs.chan._
import ru.maxkar.cs.util.BufferPool


/** Server process implementation. */
private class Server(pool : BufferPool) {


  private def afterAccept(channel : SocketChannel, selector : Selector, now : Long) : Unit = {
    try {
      channel.configureBlocking(false)

      val (b1, b2, b3, cleaner) = pool.get()
      val clientKey = channel.register(selector, SelectionKey.OP_READ, null)
      val writer = new MessageWriter(b1, b2, clientKey)
      val pinger = new Pinger(10000, 30000, writer.ping)
      val reader = new MessageReader(b3,
        msg ⇒
          if (msg == null)
            channel.close()
          else
            writer.send(msg)
      , writer.pingReply, pinger.resetPing, 10000)


      clientKey.attach(new ServerContext(
        IOHandler.communicationHandler(
          onRead = (s, n, c) ⇒ reader.doRead(s, n),
          onWrite = (s, n, c) ⇒ writer.doWrite(s, n),
          onPing = (s, n, c) ⇒ pinger.doPing(s, n),
          onError = Multiplexor.printStackAndClose),
        cleaner))
    } catch {
      case t : IOException ⇒
        channel.close()
        t.printStackTrace()
      case t : Throwable ⇒
        channel.close()
        throw t
    }
  }


  private val serverHandlers =
    IOHandler.autoAcceptor(
      afterAccept = afterAccept,
      onError = Multiplexor.printStackAndAbort)


  private val ioHandlers =
    IOHandler.polymorph[ServerContext](c ⇒ c.ioVector)
}



/** Server processor. */
private [cs] object Server {

  def serve(address : InetSocketAddress) : Unit = {
    val serverChannel = ServerSocketChannel.open()
    serverChannel.configureBlocking(false)
    serverChannel.bind(address, 10)

    val pool = new BufferPool(1024 * 1024)
    val server = new Server(pool)
    val multiplex = Multiplexor.make(server.ioHandlers, 200, 2000)

    multiplex.submit((selector, now) ⇒
      serverChannel.register(selector, SelectionKey.OP_ACCEPT,
        new ServerContext(server.serverHandlers, () => ())))

    System.in.read()
    multiplex.close()
    multiplex.awaitTermination()
    System.out.println("Done!")
  }
}
