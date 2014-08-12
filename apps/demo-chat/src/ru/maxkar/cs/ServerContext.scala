package ru.maxkar.cs


import java.nio._
import java.nio.channels._
import ru.maxkar.cs.chan._
import ru.maxkar.cs.msg._

/** Server handler context. */
private[cs] class ServerContext(
  var ioVector : IOHandler[ServerContext],
  var onData : (SelectionKey, Long, ByteBuffer, ServerContext) ⇒ Unit,
  var onEOF : (SelectionKey, Long, ServerContext) ⇒ Unit,
  var connectedContext : ConnectedClient,
  var connectingContext : ConnectingClient
  )
