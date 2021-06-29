package part1_recap

import akka.actor.{Actor, ActorSystem, Props}

object AkkaRecap extends App {

  class SimpleActor extends Actor {
    override def receive: Receive = {
      case message => println(s"I received: $message")
    }
  }

  // actor encapsulation
  val system = ActorSystem("AkkaRecap")
  // #1: you can only instantiate an actor through the actor system
  val actor = system.actorOf(Props[SimpleActor], "simpleActor")
  // #2: sending messages
  actor ! "hello"
  /*
    - messages are sent asynchronously
    - many actors (in the millions) can share a few dozen threads
    - each message is processed/handled ATOMICALLY
    - no need for locks
   */
}
