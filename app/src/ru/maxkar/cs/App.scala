package ru.maxkar.cs

import java.nio.channels._
import java.net._

/**
 * Main application launcher.
 */
final object App {
  def main(args : Array[String]) : Unit = {
    val port = 3311

    val serverChannel = ServerSocketChannel.open()
    serverChannel.bind(
      new InetSocketAddress(
        InetAddress.getLoopbackAddress(), port))
  }
}
