package part2.highlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._

object L1_HighLevelIntro extends App{
  implicit val system = ActorSystem("HighLevelIntro")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  // Directives are building blocks of high level akka http server logic. There are many many different directives, they
  // filter requests by matching criteria.. The result of composing directives is called a route
  val simpleRoute:Route = path("home") { // Directive that filter http requests that goes to /home
    complete(StatusCodes.OK) // Directive that sends back an http response with an status 200
  }

  // You can compose directives inside one another
  val pathGetRoute = path("endpoint"){
    get{
      complete(StatusCodes.OK)
    } ~ // If you don't put this tilde, the code will still compiles, so be careful
    post{
      complete(StatusCodes.Forbidden)
    }
  } ~ path("endpoint2"){ // you can chain directives at any level
    get{
      complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
        """<html>
          |<body>
          |<H1>Hello Akka!</H1>
          |</body>
          |</html>""".stripMargin))
    }
  } // The result of this chain is called routing tree

  // The style of bringing a server is the same than for the low level api, so you can set a security context in the same way
  // the route passed here is implicitly converted to a Flow
  Http().bindAndHandle(simpleRoute ~ pathGetRoute,"localhost", 8080)
}
