package ru.maxkar.cs

import java.io.IOException
import java.net._
import java.nio._
import java.nio.channels._

import ru.maxkar.cs.msg._
import ru.maxkar.cs.chan._
import ru.maxkar.cs.util.BufferPool


/** Server implementation. */
private class Server(pool : SimpleBufferPool) {

  /* Message writer for the connected client. */
  val writer = Writer.writer[ServerContext](
    _.connectedContext.writeContext, _.connectedContext.key)


  /** Message handler for a connected client. */
  private def handleMessage(
        key : SelectionKey, now : Long,
        message : Array[Byte], ctx : ServerContext)
      : Unit = {

    val itr = ctx.connectedContext.peers.iterator
    while (itr.hasNext()) {
      val item = itr.next()
      if (!item.connectedContext.key.isValid())
        itr.remove()
      else {
        try {
          writer.send(message, item)
        } catch {
          case e : IOException ⇒
            itr.remove()
            Multiplexor.printStackAndAbort(item.connectedContext.key, now, item, e)
        }
      }
    }
  }


  /** Handles a connection message. */
  private def handleConnect(
        key : SelectionKey, now : Long,
        message : Array[Byte], ctx : ServerContext)
      : Unit = {

    val b1 = pool.get()
    val b2 = pool.get()
    val cc = new ConnectedClient(
      ctx.connectingContext.queue,
      ctx.connectingContext.readBuffer,
      Pinger.context(),
      ctx.connectingContext.readContext,
      Writer.context(b1, b2),
      b1, b2, key)

    ctx.connectedContext = cc
    ctx.connectingContext = null
    ctx.onData = connectedMsgParser.update
    ctx.onEOF = connectedMsgParser.finish
  }


  /** Handler for any messages. */
  private def handleAnyData(
        key : SelectionKey, now : Long,
        message : ByteBuffer, ctx : ServerContext)
      : Unit =
    ctx.onData(key, now, message, ctx)


  /* Universal EOF handler. */
  private def handleAnyEOF(
        key : SelectionKey, now : Long,
        ctx : ServerContext)
      : Unit =
    ctx.onEOF(key, now, ctx)


  /* Releases a connected client. */
  def releaseConnected(ctx : ServerContext) : Unit = {
    var c = ctx.connectedContext
    pool.release(c.readBuffer)
    pool.release(c.wbuffer1)
    pool.release(c.wbuffer2)
    ctx.connectedContext.peers.remove(ctx.connectedContext)
  }


  def releaseConnecting(ctx : ServerContext) : Unit = {
    var c = ctx.connectingContext
    pool.release(c.readBuffer)
  }


  /** Failure handler for a connected client. */
  def failureConnected(key : SelectionKey, now : Long, ctx : ServerContext, e : Throwable) : Unit = {
    releaseConnected(ctx)
    Multiplexor.printStackAndClose(key, now, ctx, e)
  }


  /** Failure handler for a connecting client. */
  def failureConnecting(key : SelectionKey, now : Long, ctx : ServerContext, e : Throwable) : Unit = {
    releaseConnecting(ctx)
    Multiplexor.printStackAndClose(key, now, ctx, e)
  }


  /** EOF handler for a connected client. */
  def eofConnected(key : SelectionKey, now : Long, ctx : ServerContext) : Unit = {
    try {
      key.channel().close()
    } finally {
      releaseConnected(ctx)
    }
  }


  /** EOF handler for a connecting client. */
  def eofConnecting(key : SelectionKey, now : Long, ctx : ServerContext) : Unit = {
    try {
      key.channel().close()
    } finally {
      releaseConnecting(ctx)
    }
  }


  /** Pings a connecting client. */
  def pingConnecting(key : SelectionKey, now : Long, ctx : ServerContext) : Unit = {
    if (now > ctx.connectingContext.connectDeadline)
      throw new IOException("No connection!")
  }


  /** Read handler. */
  private val readHandler = Transport.byteBufferReader[ServerContext](
    c ⇒
      if (c.connectingContext != null)
        c.connectingContext.readBuffer
      else
        c.connectedContext.readBuffer,
    onMessage = handleAnyData,
    onEof = handleAnyEOF)



  /** Pinger for the connected client. */
  val connectedPinger = Pinger.pinger[ServerContext](
    _.connectedContext.pingContext, 10000, 30000, writer.ping)


  /** Parser for the connected client. */
  val connectedMsgParser = Parser.parser[ServerContext](
    _.connectedContext.readContext,
    onMessage = handleMessage,
    onEof = eofConnected,
    onPingRequest = writer.pingReply,
    onPingResponse = connectedPinger.reset,
    messageSizeLimit = 10000)


  /** Handler for the connected client. */
  private val connectedHandler =
    IOHandler.communicationHandler(
      onRead = readHandler,
      onWrite = writer.doWrite,
      onPing = connectedPinger.doPing,
      onError = failureConnected)


  /** Handler for the connecting client. */
  private val connectingHandler =
    IOHandler.communicationHandler[ServerContext](
      onRead = readHandler,
      onWrite = (k, n, c) ⇒ (),
      onPing = pingConnecting,
      onError = failureConnecting)


  /** Input-output vector. */
  private val ioHandlers =
    IOHandler.polymorph[ServerContext](c ⇒ c.ioVector)
}


/** Server access point. */
private [cs] object Server {


  def serve(address : InetSocketAddress) : Unit = {
    val serverChannel = ServerSocketChannel.open()
    serverChannel.configureBlocking(false)
    serverChannel.bind(address, 10)

    val pool = new SimpleBufferPool(1024 * 1024)
    val server = new Server(pool)
    val multiplex = Multiplexor.make(server.ioHandlers, 200, 2000)

//    multiplex.submit((selector, now) ⇒
//      serverChannel.register(selector, SelectionKey.OP_ACCEPT,
//        new ServerContext(server.serverHandlers, null, null, null, null, null, () => ())))

    System.in.read()
//    multiplex.close()
//    multiplex.awaitTermination()
    System.out.println("Done!")
  }
}
