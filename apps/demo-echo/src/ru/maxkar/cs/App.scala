package ru.maxkar.cs

import java.nio.channels._
import java.nio._
import java.io._
import java.net._

import ru.maxkar.cs.chan.Multiplexor
import ru.maxkar.cs.util.BufferPool

/**
 * Main application launcher.
 */
final object App {
  val port = 3311

  def main(args : Array[String]) : Unit = {
    if (args.length > 0)
      Client.work(serverAddress())
    else
      Server.serve(serverAddress())
  }

  private def serverAddress() : InetSocketAddress =
    new InetSocketAddress(
      InetAddress.getLoopbackAddress(), port)
}
