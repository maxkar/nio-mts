package ru.maxkar.cs

import java.nio.channels._

import ru.maxkar.cs.chan.IOHandler

/** IO context for the server task.
 * @param ioVector input/output vector for the channel.
 * @param cleaner context cleanup function (for client channels).
 * @param serverChannel optional server channel.
 * @param clientContext context used for serving one client.
 */
private [cs] final class ServerContext(
    val ioVector : IOHandler[ServerContext],
    val cleaner : () ⇒ Unit
    )
