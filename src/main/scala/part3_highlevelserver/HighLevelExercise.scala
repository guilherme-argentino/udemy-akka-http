package part3_highlevelserver

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.stream.ActorMaterializer
import akka.util.Timeout
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import part3_highlevelserver.PeopleDB.PersonCreated
import spray.json._

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

trait PersonStoreJsonProtocol extends SprayJsonSupport with DefaultJsonProtocol {

  implicit val personFormat: RootJsonFormat[Person] = jsonFormat2(Person.apply)
  implicit val personCreatedFormat: RootJsonFormat[PersonCreated] = jsonFormat1(PersonCreated)

}

object HighLevelExercise extends App with PersonStoreJsonProtocol {

  implicit val system: ActorSystem = ActorSystem("HighLevelExercise")
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  import system.dispatcher
  import PeopleDB._

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

  val people = List(
    Person(1, "Alice"),
    Person(2, "Bob"),
    Person(3, "Charlie")
  )

  /*
    1. setup
   */
  val peopleDb = system.actorOf(props = Props[PeopleDB])

  people.foreach { person =>
    peopleDb ! CreateOrUpdatePerson(person)
  }

  def toHttpEntity(payload: String) = HttpEntity(ContentTypes.`application/json`, payload)

  implicit val timeout: Timeout = Timeout(2 seconds)
  val peopleServerRoute =
    pathPrefix("api" / "people") {
      get {
        (path(IntNumber) | parameter('pin.as[Int])) { pin =>
          complete(
            (peopleDb ? FindPerson(pin))
              .mapTo[Option[Person]]
              .map(_.toJson.prettyPrint)
              .map(toHttpEntity)
          )
        } ~ pathEndOrSingleSlash {
          complete(
            (peopleDb ? FindAllPeople)
              .mapTo[List[Person]]
              .map(_.toJson.prettyPrint)
              .map(toHttpEntity)
          )
        }
      } ~ post {
        entity(as[Person]) { person =>
          complete(
            (peopleDb ? CreateOrUpdatePerson(person))
              .mapTo[PersonCreated]
              .map(_.toJson.prettyPrint)
              .map(toHttpEntity)
          )
        }
      }
    }

  Http().bindAndHandle(peopleServerRoute, "localhost", 8080)
}

case class Person(pin: Int, name: String)

object PeopleDB {

  case class CreateOrUpdatePerson(person: Person)

  case class PersonCreated(pin: Int)

  case class FindPerson(id: Int)

  case object FindAllPeople

}

class PeopleDB extends Actor with ActorLogging {

  import PeopleDB._

  var people: Map[Int, Person] = Map()

  override def receive: Receive = {
    case FindAllPeople =>
      log.info("Searching for all people")
      sender() ! people.values.toList
    case FindPerson(pin) =>
      log.info(s"Searching person by pin: $pin")
      sender() ! people.get(pin)
    case CreateOrUpdatePerson(person) =>
      people.get(person.pin) match {
        case None =>
          log.info(s"Adding person $person with pin ${person.pin}")
        case Some(person) =>
          log.info(s"Updating person $person with pin ${person.pin}")
      }
      people = people + (person.pin -> person)
      sender() ! PersonCreated(person.pin)
  }
}
