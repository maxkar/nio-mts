package ru.maxkar.cs

import java.nio.ByteBuffer
import java.nio.channels._
import ru.maxkar.cs.msg._


/**
 * Context for the accepted and servable client.
 * @param channel socket used for the connections.
 * @param reader read handler.
 * @param writer write handler.
 * @param pinger ping handler.
 * @param buffers buffers used for the client.
 */
private [cs] final class ServerClientContext(
    val reader : MessageReader,
    val writer : MessageWriter,
    val pinger : Pinger,
    val bufferCleaner : () ⇒ Unit) {
}
