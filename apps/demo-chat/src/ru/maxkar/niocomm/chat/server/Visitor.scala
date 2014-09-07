package ru.maxkar.niocomm.chat.server


import ru.maxkar.niocomm.MessageIO

/**
 * Chat room visitor.
 * @param name visitor name.
 * @param context message exchange context.
 */
private[server] final class Visitor(
      val name : String,
      context : MessageIO) {

  /** Outgoing messages. */
  val messages = context.outData
}
