package part3_highlevelserver

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer

object HighLevelExercise extends App {

  implicit val system = ActorSystem("HighLevelExercise")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  /**
   * Exercise.
   *
   * - GET /api/people: retrieve ALL the people you have registered
   * - GET /api/people/pin: retrieve the person with that PIN return as JSON
   * - GET /api/people?pin=X (same)
   * - (harder) POST /api/people with a JSON payload denoting a Person add that person to your database
   *  - extract the HTTP request's payload (entity)
   *    - extract the request
   *    - process the entity's data
   */

  case class Person(pin: Int, name: String)

  val people = List(
    Person(1, "Alice"),
    Person(2, "Bob"),
    Person(3, "Charlie")
  )
}
