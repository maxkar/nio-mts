package ru.maxkar.cs

import java.nio.channels._
import java.nio.ByteBuffer

import ru.maxkar.cs.chan.IOHandler
import ru.maxkar.cs.msg._

/** IO context for the server task.
 * @param ioVector input/output vector for the channel.
 * @param readBuffer data read buffer.
 * @param cleaner context cleanup function (for client channels).
 * @param serverChannel optional server channel.
 * @param clientContext context used for serving one client.
 */
private [cs] final class ServerContext(
    val ioVector : IOHandler[ServerContext],
    var readBuffer : ByteBuffer,
    val pingContext : Pinger.Context,
    var readContext : Parser.Context,
    val writeContext : Writer.Context,
    val key : SelectionKey,
    val cleaner : () ⇒ Unit
    )
