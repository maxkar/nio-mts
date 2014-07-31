package ru.maxkar.cs

import java.io._
import java.net._
import java.nio.channels._

import ru.maxkar.cs.msg._
import ru.maxkar.cs.chan._
import ru.maxkar.cs.util.BufferPool


/** Server process implementation. */
private class Server(pool : BufferPool) {

  private def doAccept(key : SelectionKey, now : Long, ctx : ServerContext) : Unit = {
    if (ctx.serverChannel == null)
      return
    val clientSocket = ctx.serverChannel.accept()
    if (clientSocket == null)
      return

    try {
      clientSocket.configureBlocking(false)

      val (b1, b2, b3, cleaner) = pool.get()
      val clientKey = clientSocket.register(key.selector(), SelectionKey.OP_READ, null)
      val writer = new MessageWriter(b1, b2, clientKey)
      val pinger = new Pinger(10000, 30000, writer.ping)
      val reader = new MessageReader(b3,
        msg ⇒
          if (msg == null)
            clientSocket.close()
          else
            writer.send(msg)
      , writer.pingReply, pinger.resetPing, 10000)

      clientKey.attach(
        new ServerContext(null, new ServerClientContext(reader, writer, pinger, cleaner)))
    } catch {
      case t : IOException ⇒
        clientSocket.close()
        t.printStackTrace()
    }
  }


  private def doRead(key : SelectionKey, now : Long, ctx : ServerContext) : Unit =
    if (ctx.clientContext != null)
      ctx.clientContext.reader.doRead(key, now)


  private def doWrite(key : SelectionKey, now : Long, ctx : ServerContext) : Unit =
    if (ctx.clientContext != null)
      ctx.clientContext.writer.doWrite(key, now)


  private def doPing(key : SelectionKey, now : Long, ctx : ServerContext) : Unit =
    if (ctx.clientContext != null)
      ctx.clientContext.pinger.doPing(key, now)


  private def handleError(
        key : SelectionKey,
        now : Long,
        ctx : ServerContext,
        err : Throwable)
      : Unit = {
    if (ctx.serverChannel != null)
      Multiplexor.printStackAndAbort(key, now, ctx, err)
    else
      Multiplexor.printStackAndClose(key, now, ctx, err)
  }


  private val ioHandlers = new IOHandler[ServerContext](
    onAccept = doAccept,
    onConnect = (k, s, n) ⇒ k.interestOps(k.interestOps() & ~SelectionKey.OP_CONNECT),
    onRead = doRead,
    onWrite = doWrite,
    onPing = doPing,
    onError = handleError
  )
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
        new ServerContext(serverChannel, null)))

    System.in.read()
    multiplex.close()
    multiplex.awaitTermination()
    System.out.println("Done!")
  }
}
