package ru.maxkar.cs

import java.nio._
import java.nio.channels._
import ru.maxkar.cs.msg._

/** Client connected to a server. */
private[cs] final class ConnectedClient(
    val peers : java.util.List[ServerContext],
    val readBuffer : ByteBuffer,
    val pingContext : Pinger.Context,
    val readContext : Parser.Context,
    val writeContext : Writer.Context,
    val wbuffer1 : ByteBuffer,
    val wbuffer2 : ByteBuffer,
    val key : SelectionKey)
