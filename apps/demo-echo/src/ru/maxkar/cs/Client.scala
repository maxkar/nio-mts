package ru.maxkar.cs

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio._
import java.nio.channels._
import java.net._

import ru.maxkar.cs.chan._
import ru.maxkar.cs.msg._

/**
 * Echo client.
 */
final object Client {
  private class ClientContext(
      val handler : IOHandler[ClientContext],
      val readBuf : ByteBuffer,
      val parseContext : Parser.Context,
      val pingContext : Pinger.Context,
      val writeContext : Writer.Context,
      val key : SelectionKey)


  private val writer =
    Writer.writer[ClientContext](
      c ⇒ c.writeContext,
      c ⇒ c.key)


  private val pinger = Pinger.pinger[ClientContext](
    c ⇒ c.pingContext, 10000, 30000, writer.ping)


  private val messageParser =
    Parser.parser[ClientContext](
      c ⇒ c.parseContext,
      onMessage = (k, n, msg, c) ⇒ System.out.println(new String(msg, "UTF-8")),
      onEof = (k, n, c) ⇒ {
        k.channel().close()
        k.selector().close()
        System.exit(0)
      },
      onPingRequest = writer.pingReply,
      onPingResponse = pinger.reset
    )


  private val readHandler =
    Transport.byteBufferReader[ClientContext](
      c ⇒ c.readBuf,
      onMessage = messageParser.update,
      onEof = messageParser.finish)




  def afterConnect(multiplex : Multiplexor[_],
        key : SelectionKey, chan : SocketChannel, now : Long, context : ClientContext) : Unit = {
    val readBuf = ByteBuffer.allocateDirect(1024)
    val write1 = ByteBuffer.allocateDirect(1024)
    val write2 = ByteBuffer.allocateDirect(1024)

    key.interestOps(SelectionKey.OP_READ)

    val c = new ClientContext(IOHandler.communicationHandler(
      onRead = readHandler,
      onWrite = writer.doWrite,
      onPing = pinger.doPing,
      onError = Multiplexor.printStackAndAbort),
      readBuf,
      Parser.context(), Pinger.context(), Writer.context(write1, write2), key)
    key.attach(c)


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
            writer.send(line.getBytes("UTF-8"), c))
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
          connectDeadline = now + 5000), null, null, null, null, null)))

    multiplex.awaitTermination()
  }
}
