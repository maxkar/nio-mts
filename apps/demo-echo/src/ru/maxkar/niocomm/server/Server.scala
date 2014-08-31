package ru.maxkar.niocomm.server


import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels._

import scala.collection.mutable.Queue

/**
 * Message echo server implementation.
 * @param mainChannel main (server socket) channel.
 */
final class Server(mainChannel : ServerSocketChannel) {

  /** Interval between pings. */
  private val pingInterval = 3000


  /** IO buffer size. */
  private val ioBufferSize = 8192


  /** Work buffers. */
  private val buffers = new Queue[ByteBuffer]



  /** Flag indicating that server should be closed. */
  @volatile
  private var shouldClose = false



  /** ACtive selector. */
  private val selector = Selector.open



  /** Worker thread. */
  private val worker = new Thread(new Runnable() {
    override def run() : Unit = serve()
  })



  /** Closes the server by requesting a "close" operation and awaiting termination. */
  def close() : Unit = {
    shouldClose = true
    selector.wakeup()
    worker.join()
  }



  /** Perform all operations. */
  private def serve() : Unit = {
    try {
      mainChannel.register(
        selector, SelectionKey.OP_ACCEPT,
        new ServerState(allocBuffer))
      serveLoop()
    } finally {
      val itr = selector.keys().iterator()
      while (itr.hasNext())
        itr.next().channel().close
      selector.close()
    }
  }



  /** Performs a serving loop. */
  private def serveLoop() : Unit = {
    var nextPing = System.currentTimeMillis() + pingInterval
    while (mainChannel.isOpen()) {
      val timeout = Math.max(100, nextPing - System.currentTimeMillis())
      selector.select(timeout)

      val now = System.currentTimeMillis()

      handleIO(now)

      if (nextPing < now) {
        nextPing = now + pingInterval
        handlePing(now)
      }


      if (shouldClose)
        mainChannel.close()
    }
  }



  /** Handles a ping. */
  private def handlePing(now : Long) : Unit = {
    val itr = selector.keys.iterator()

    while (itr.hasNext()) {
      val key = itr.next()
      val state = key.attachment().asInstanceOf[State]

      try {
        state.onPing(key, now)
      } catch {
        case e : IOException ⇒
          e.printStackTrace()
          buffers ++= state.close(key)
          key.channel().close()
      }
    }
  }




  /** Handles an input/output on the selector. */
  private def handleIO(now : Long) : Unit = {
    val itr = selector.selectedKeys.iterator()

    while (itr.hasNext()) {
      val key = itr.next()
      itr.remove()
      val state = key.attachment().asInstanceOf[State]

      try {
        state.onSelection(key, now)
      } catch {
        case e : IOException ⇒
          e.printStackTrace()
          buffers ++= state.close(key)
      }
    }
  }



  /** Allocates a new byte buffer. */
  private def allocBuffer() : ByteBuffer =
    if (buffers.isEmpty)
      ByteBuffer.allocateDirect(ioBufferSize)
    else
      buffers.dequeue()
}




/**
 * Server access point.
 */
final object Server {
  /** Serves the data. */
  def serve(address : InetSocketAddress) : Server = {
    val chan = ServerSocketChannel.open()
    chan.configureBlocking(false)
    chan.bind(address, 10)

    val res = new Server(chan)
    res.worker.start()
    res
  }



  /**
   * Performs a main serving loop.
   */
  def serveMain(address : InetSocketAddress) : Unit = {
    val server = serve(address)
    System.in.read()
    server.close()
  }
}
