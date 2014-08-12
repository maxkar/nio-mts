package ru.maxkar.cs


import java.net._


/** Application launcher. */
final object App {

  private val port = 3312

  def main(args : Array[String]) : Unit = {
    Server.serve(serverAddress())
  }

  private def serverAddress() : InetSocketAddress =
    new InetSocketAddress(
      InetAddress.getLoopbackAddress(), port)
}