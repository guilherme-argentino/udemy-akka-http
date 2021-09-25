package part3_highlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.ExceptionHandler
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._

object HandleExceptions extends App {

  implicit val system: ActorSystem = ActorSystem("HandlingExceptions")
  implicit val materializer: ActorMaterializer = ActorMaterializer()
  import system.dispatcher

  val simpleRoute =
    path("api" / "people") {
      get {
        // directive that throws some exception
        throw new RuntimeException("Getting all the people took too long")
      } ~
        post {
          parameter('id) { id =>
            if (id.length > 2)
              throw new NoSuchElementException(s"Parameter $id cannot be found in the database, TABLE FLIP!")

            complete(StatusCodes.OK)
          }
        }
    }

  implicit val customExceptionHandler: ExceptionHandler = ExceptionHandler {
    case e: RuntimeException =>
      complete(StatusCodes.NotFound, e.getMessage)
    case e: IllegalArgumentException =>
      complete(StatusCodes.BadRequest, e.getMessage)
  }


  Http().bindAndHandle(simpleRoute, "localhost", 8080)

}
