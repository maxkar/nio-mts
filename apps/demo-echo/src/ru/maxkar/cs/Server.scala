package ru.maxkar.cs

import java.io.IOException
import java.net._
import java.nio.channels._

import ru.maxkar.cs.msg._
import ru.maxkar.cs.chan._
import ru.maxkar.cs.util.BufferPool


/** Server process implementation. */
private class Server(pool : BufferPool) {

  /** Message writer. */
  private val writer = Writer.writer[ServerContext](
    _.writeContext, _.key)


  /** Write message handler. */
  private def messageHandler(k : SelectionKey, n : Long, msg : Array[Byte], c : ServerContext) : Unit =
    writer.send(msg, c)


  /** End-of-file handler. */
  private def eofHandler(k : SelectionKey, n : Long, c : ServerContext) : Unit =
    try {
      k.channel.close()
    } finally {
      c.cleaner()
    }



  /** Ping handler. */
  private val pinger = Pinger.pinger[ServerContext](
    c ⇒ c.pingContext, 10000, 30000, writer.ping)


  /** Message parser. */
  private val msgParser = Parser.parser[ServerContext](
    c ⇒ c.readContext,
    onMessage = messageHandler,
    onEof = eofHandler,
    onPingRequest = writer.pingReply,
    onPingResponse = pinger.reset,
    messageSizeLimit = 10000)


  /** Read handler. */
  private val readHandler = Transport.byteBufferReader[ServerContext](
    c ⇒ c.readBuffer,
    onMessage = msgParser.update,
    onEof = msgParser.finish)


  private def afterAccept(channel : SocketChannel, selector : Selector, now : Long) : Unit = {
    try {
      channel.configureBlocking(false)

      val (b1, b2, b3, cleaner) = pool.get()
      val clientKey = channel.register(selector, SelectionKey.OP_READ, null)


      clientKey.attach(new ServerContext(
        IOHandler.communicationHandler(
          onRead = readHandler,
          onWrite = writer.doWrite,
          onPing = pinger.doPing,
          onError = Multiplexor.printStackAndClose),
        b3,
        Pinger.context(),
        Parser.context(),
        Writer.context(b1, b2),
        clientKey,
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
        new ServerContext(server.serverHandlers, null, null, null, null, null, () => ())))

    System.in.read()
    multiplex.close()
    multiplex.awaitTermination()
    System.out.println("Done!")
  }
}
