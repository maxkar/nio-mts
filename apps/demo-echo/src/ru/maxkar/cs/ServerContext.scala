package ru.maxkar.cs

import java.nio.channels._

/** IO context for the server task.
 * @param serverChannel optional server channel.
 * @param clientContext context used for serving one client.
 */
private [cs] final class ServerContext(
    val serverChannel : ServerSocketChannel = null,
    val clientContext : ServerClientContext = null
    )
