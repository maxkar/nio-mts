package ru.maxkar.cs

import java.nio._
import java.nio.channels._
import ru.maxkar.cs.msg._

/** Client during the connection process. */
private[cs] class ConnectingClient(
    val queue : java.util.List[ServerContext],
    val connectDeadline : Long,
    val readContext : Parser.Context,
    val readBuffer : ByteBuffer,
    val key : SelectionKey)

