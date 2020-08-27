package part2.highlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.server.{MethodRejection, MissingQueryParamRejection}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Rejection, RejectionHandler}
import akka.stream.ActorMaterializer
import akka.util.Timeout

import scala.concurrent.duration._

object L6_HandlingRejections extends App {
  implicit val system = ActorSystem("HandlingRejections")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(3 seconds)

  val simpleRoute = path("api" / "myEndpoint") {
    get {
      complete(StatusCodes.OK)
    } ~ parameter('id.as[Int]) { id =>
      complete(StatusCodes.OK)
    }
  }
  // A rejected doesn't match a directive, it passes on the request to another branch of the routing tree, it is not a
  // failure. Rejections are aggregated into a list of rejected directives, and if later on any directive matches, this
  // list is cleared. We can handle the rejection behaviour with the rejection handlers which are a function between
  // (immutable.Seq[Rejection] ⇒ Option[Route])
  val badRequestHandler: RejectionHandler = { rejectionList: Seq[Rejection] ⇒
    println(s"Encounter Rejections ${rejectionList}")
    Some(complete(StatusCodes.BadRequest)) // <= This is an Option[Route]
  }

  val forbidenHandler: RejectionHandler = { rejectionList: Seq[Rejection] ⇒
    println("Encounter Forbidden Rejection")
    Some(complete(StatusCodes.Forbidden))
  }

  //we can add the above to our route
  val simpleRejectionHandlerRoute = handleRejections(badRequestHandler) { // Define server logic here
    path("api" / "myEndpoint")
    get { // if this rejects the request, then badRequestHandler will execute
      complete(StatusCodes.OK)
    } ~ post {
      handleRejections(forbidenHandler) {
        parameter('myParam) { myParam =>
          println(s"Received ${myParam}")
          complete(StatusCodes.OK)
        }
      }
    }
  }
  // If the request is rejected but there is no rejection elements in the rejection list, it means the request was not found
  // This is regardless of whether you add a rejection handler or not, there is always an implicit top level rejection
  // handler which is accessed with RejectionHandler.default. This  default rejection handler can be overwritten defining
  // a new implicit rejection handler:
  implicit val customDefaultRejectionHandler = RejectionHandler.newBuilder().handle { // Takes a PF between Rejection and Route
    case m: MethodRejection => complete(StatusCodes.ImATeapot)
  }.handleAll[MissingQueryParamRejection]{ r =>
    //  Handles all MissingQueryParamRejection rejection types
    // Adding multiple handlers here sets a priority on the rejection directives. Multiple handlers with a single
    // case on each is different from multiple cases with a single handle
    complete(StatusCodes.InternalServerError)
  }.handleNotFound{ // In case the list of rejections is empty
    complete(StatusCodes.NotFound)
  }result()

  // Having an implicit rejection handler in place is called sealing a route, because no matter with http request you get,
  // you always have a defined action to it.

  Http().bindAndHandle(simpleRejectionHandlerRoute, "localhost", 8080)
}
