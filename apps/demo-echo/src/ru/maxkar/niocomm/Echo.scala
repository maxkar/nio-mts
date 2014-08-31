package ru.maxkar.niocomm


import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Main echo driver.
 */
final object Echo {
  val port = 3311

  val address =
    new InetSocketAddress(
      InetAddress.getLoopbackAddress(), port)

  def main(args : Array[String]) : Unit = {
    if (args.length == 0)
      server.Server.serveMain(address)
    else
      client.Client.doMain(address)
  }
}
