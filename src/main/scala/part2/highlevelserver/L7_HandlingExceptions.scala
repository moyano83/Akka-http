package part2.highlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.ExceptionHandler
import akka.stream.ActorMaterializer
import akka.util.Timeout

import scala.concurrent.duration._

object L7_HandlingExceptions extends App {
  implicit val system = ActorSystem("HandlingExceptions")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(3 seconds)

  import system.dispatcher

  // There is a default Exception handler which will reply with a 500
  val simpleRoute = path("api" / "people") {
    get {
      throw new RuntimeException("Timeout") // simulation of an error in one of the Directives
    } ~
      post {
        parameter('id) { id =>
          if (id.length > 2)
            throw new NoSuchElementException("Parameter id can not be found in the DB")
          complete(StatusCodes.OK)
        }
      }
  }

  // By making this implicit, it will be used to manage exceptions on Directives. If for whatever reason the PF does not
  // match with the exception thrown, the default Exception handler will kick in, returning a 500
  implicit val exceptionHandler: ExceptionHandler = ExceptionHandler { //  This is a PF between a Throwable and a Route
    // we can decide a course of actions depending on the error other than the 500
    case e: RuntimeException => complete(StatusCodes.InternalServerError, e.getMessage) // The e.message will be returned
    case e: NoSuchElementException => complete(StatusCodes.NotFound, e.getMessage)
  }

  // You can also do explicit Exception handler in your directives
  val exHandler1 = ExceptionHandler {
    case e: RuntimeException => complete(StatusCodes.BadRequest, e.getMessage)
  }
  val exHandler2 = ExceptionHandler {
    case e: NoSuchElementException => complete(StatusCodes.NotFound, e.getMessage)
  }
  //Http().bindAndHandle(simpleRoute, "localhost", 8080)

  // Then define the directives with the specific exception handlers
  val simpleRouteWithExceptionHandlers = {
    handleExceptions(exHandler1) {
      path("api" / "people") {
        get {
          throw new RuntimeException("Timeout") // simulation of an error in one of the Directives
        } ~
          post {
            handleExceptions(exHandler2) {
              parameter('id) { id =>
                if (id.length > 2)
                  throw new NoSuchElementException("Parameter id can not be found in the DB")
                complete(StatusCodes.OK)
              }
            }
          }
      }
    }
  }

  Http().bindAndHandle(simpleRouteWithExceptionHandlers, "localhost", 8080)
}
