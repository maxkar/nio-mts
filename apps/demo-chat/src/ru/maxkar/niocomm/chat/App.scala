package ru.maxkar.niocomm.chat


import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Main echo driver.
 */
final object App {
  val port = 3311

  val address =
    new InetSocketAddress(
      InetAddress.getLoopbackAddress(), port)

  def main(args : Array[String]) : Unit = {
    if (args.length == 0)
      server.Server.serve(address)
    else
      client.Client.handle(args(0), address)
  }
}
