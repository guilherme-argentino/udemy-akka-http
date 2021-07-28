package part2_lowlevelserver

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes, Uri}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import part2_lowlevelserver.GuitarDB.{CreateGuitar, FindAllGuitars, FindAllGuitarsWithStock, FindGuitar, GuitarCreated, GuitarUpdated, UpdateGuitar}
import spray.json._

import scala.concurrent.Future
import scala.concurrent.duration._

case class Guitar(make: String, model: String, quantity: Option[Int] = None)

object GuitarDB {
  case class CreateGuitar(guitar: Guitar)

  case class GuitarCreated(id: Int)

  case class GuitarUpdated(id: Int)

  case class FindGuitar(id: Int)

  case class UpdateGuitar(id: Int, guitar: Guitar)

  case object FindAllGuitars

  case class FindAllGuitarsWithStock(flag: Boolean)
}

class GuitarDB extends Actor with ActorLogging {

  import GuitarDB._

  var guitars: Map[Int, Guitar] = Map()
  var currentGuitarId: Int = 0

  override def receive: Receive = {
    case FindAllGuitars =>
      log.info("Searching for all guitars")
      sender() ! guitars.values.toList
    case FindAllGuitarsWithStock(flag: Boolean) =>
      log.info(s"Searching for all guitars in Stock: $flag")
      sender() ! guitars.values.filter(g => g.quantity.exists(_ > 0) == flag).toList
    case FindGuitar(id) =>
      log.info(s"Searching guitar by id: $id")
      sender() ! guitars.get(id)
    case CreateGuitar(guitar) =>
      log.info(s"Adding guitar $guitar with id $currentGuitarId")
      guitars = guitars + (currentGuitarId -> guitar)
      sender() ! GuitarCreated(currentGuitarId)
      currentGuitarId += 1
    case UpdateGuitar(id: Int, guitar: Guitar) =>
      log.info(s"Updating guitar $guitar 's with id $id")
      guitars = guitars + (id -> guitar)
      sender() ! GuitarUpdated(id)
  }
}

trait GuitarStoreJsonProtocol extends DefaultJsonProtocol {

  implicit val guitarFormat = jsonFormat3(Guitar)

}

object LowLevelRest extends App with GuitarStoreJsonProtocol {

  implicit val system = ActorSystem("LowLevelRest")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher // not for production

  /*
    - GET on localhost:8080/api/guitar => ALL the guitars in the store
    X GET on localhost:8080/api/guitar?id=X => fetches the guitar associated with id X
    - POST on localhost:8080/api/guitar => insert the guitar into the store
   */

  // JSON -> marshalling
  val simpleGuitar = Guitar("Fender", "Stratocaster")
  println(simpleGuitar.toJson.prettyPrint)

  // unmarshalling
  val simpleGuitarJsonString =
    """
      |{
      |  "make": "Fender",
      |  "model": "Stratocaster"
      |}
      |""".stripMargin
  println(simpleGuitarJsonString.parseJson.convertTo[Guitar])

  /*
    setup
   */
  val guitarDb = system.actorOf(Props[GuitarDB], "LowLevelGuitarDB")
  val guitarList = List(
    Guitar("Fender", "Stratocaster"),
    Guitar("Gibson", "Les Paul"),
    Guitar("Martin", "LX1")
  )

  guitarList.foreach { guitar =>
    guitarDb ! CreateGuitar(guitar)
  }

  /*
    server code
   */
  implicit val defaultTimeout = Timeout(2 seconds)

  def getGuitar(query: Query): Future[HttpResponse] = {
    val guitarId = query.get("id").map(_.toInt) // Option[Int]

    guitarId match {
      case None => Future(HttpResponse(StatusCodes.NotFound)) // /api/guitar?id=
      case Some(id: Int) =>
        val guitarFuture: Future[Option[Guitar]] = (guitarDb ? FindGuitar(id)).mapTo[Option[Guitar]]
        guitarFuture.map {
          case None => HttpResponse(StatusCodes.NotFound) // /api/guitar?id=9000
          case Some(guitar) =>
            HttpResponse(
              entity = HttpEntity(
                ContentTypes.`application/json`,
                guitar.toJson.prettyPrint
              )
            )
        }
    }
  }

  def getGuitarWithInventory(query: Query): Future[HttpResponse] = {
    val inStock = query.get("inStock").map(_.toBoolean)

    inStock match {
      case None => Future(HttpResponse(StatusCodes.NotFound))
      case Some(inStock: Boolean) =>
        val guitarsFuture: Future[List[Guitar]] = (guitarDb ? FindAllGuitarsWithStock(inStock)).mapTo[List[Guitar]]
        guitarsFuture.map { guitars =>
          HttpResponse(
            entity = HttpEntity(
              ContentTypes.`application/json`,
              guitars.toJson.prettyPrint
            )
          )
        }
    }
  }

  def updateGuitar(query: Query): Future[HttpResponse] = {
    val id = query.get("id").map(_.toInt)
    val quantity = query.get("quantity").map(_.toInt)

    (id, quantity) match {
      case (Some(id), Some(quantity)) =>
        val guitarFuture: Future[Option[Guitar]] = (guitarDb ? FindGuitar(id)).mapTo[Option[Guitar]]
        guitarFuture.flatMap {
          case None => Future(HttpResponse(StatusCodes.NotFound)) // /api/guitar?id=9000
          case Some(guitar) =>
            val finalQuantity: Option[Int] = (guitar.quantity ++ Some(quantity)).reduceOption(_ + _)
            val finalGuitar = guitar.copy(quantity = finalQuantity)

            val guitarUpdatedFuture: Future[GuitarUpdated] = (guitarDb ? UpdateGuitar(id, finalGuitar)).mapTo[GuitarUpdated]
            guitarUpdatedFuture.map { _ =>
              HttpResponse(StatusCodes.OK)
            }
        }
      case _ => Future(HttpResponse(StatusCodes.NotFound))
    }
  }

  val requestHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar"), _, _, _) =>
      /*
        query parameter handling code
       */
      val query = uri.query() // query object <=> Map[String, String]

      if (query.isEmpty) {
        val guitarsFuture: Future[List[Guitar]] = (guitarDb ? FindAllGuitars).mapTo[List[Guitar]]
        guitarsFuture.map { guitars =>
          HttpResponse(
            entity = HttpEntity(
              ContentTypes.`application/json`,
              guitars.toJson.prettyPrint
            )
          )
        }
      } else {
        // fetch guitar associated to the guitar id
        // localhost:8080/api/guitar?id=45
        getGuitar(query)
      }

    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar/inventory"), _, _, _) =>
      /*
        query parameter handling code
       */
      val query = uri.query() // query object <=> Map[String, String]

      if (query.nonEmpty) {
        getGuitarWithInventory(query)
      } else {
        Future(HttpResponse(StatusCodes.NotFound))
      }


    case HttpRequest(HttpMethods.POST, Uri.Path("/api/guitar"), _, entity: HttpEntity, _) =>
      // entities are a Source[ByteString]
      val strictEntityFuture = entity.toStrict(3 seconds)
      strictEntityFuture.flatMap { strictEntity =>
        val guitarJsonString = strictEntity.data.utf8String
        val guitar = guitarJsonString.parseJson.convertTo[Guitar]

        val guitarCreatedFuture: Future[GuitarCreated] = (guitarDb ? CreateGuitar(guitar)).mapTo[GuitarCreated]
        guitarCreatedFuture.map { _ =>
          HttpResponse(StatusCodes.OK)
        }
      }


    case HttpRequest(HttpMethods.POST, uri@Uri.Path("/api/guitar/inventory"), _, entity: HttpEntity, _) =>
      /*
        query parameter handling code
       */
      val query = uri.query() // query object <=> Map[String, String]

      if (query.nonEmpty) {
        updateGuitar(query)
      } else {
        Future(HttpResponse(StatusCodes.NotFound))
      }

    case request: HttpRequest =>
      request.discardEntityBytes()
      Future {
        HttpResponse(status = StatusCodes.NotFound)
      }
  }

  Http().bindAndHandleAsync(requestHandler, "localhost", 8080)

  /**
   * Exercise: enhance the Guitar case class with a quantity field, by default 0
   * - GET to /api/guitar/inventory?inStock=true/false which return the guitars in stock as a JSON
   * - POST to /api/guitar/inventory?id=X&quantity=Y which adds Y guitars to the stock for guitar with id X
   */
}
