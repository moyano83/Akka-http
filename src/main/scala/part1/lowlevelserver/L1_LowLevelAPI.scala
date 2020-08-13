package part1.lowlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.IncomingConnection
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink}
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.util.{Failure, Success}

object L1_LowLevelAPI extends App {
  implicit val system = ActorSystem("LowLevelServerAPI")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  // This source is a source of incoming connections, the materialized value is of type Future[Http.ServerBinding], which
  // allows us to unbind or shutdown the server
  val serverSource = Http().bind("localhost", 8080)

  val connectionSink = Sink.foreach[IncomingConnection] { connection =>
    println(s"Accepting incoming connection from ${connection.remoteAddress}")
  }

  // To start the server we need to get a materialized value from an server source to the connectionSink

  val serverBindingFuture = serverSource.to(connectionSink).run()

  serverBindingFuture.onComplete {
    case Success(binding) => {
      println("Server binding successful")
      //When can programatically terminate the server with the return object from the binding operation
      binding.unbind() // Keeps incoming connections alive
      binding.terminate(3 seconds) // terminates incoming connections as well
    }
    case Failure(exception) => println(s"Server binding failure, ${exception}")
  }

  /**
    * Dealing with connections, we have several options.
    * Method 1: synchronously, we need to create a function HttpRequest => HttpResponse
    */
  val requestHandler: HttpRequest => HttpResponse = {
    case HttpRequest(HttpMethods.GET, _, _, _, _) => // the parameters are METHOD, URI, HEADERS, CONTENT, PROTOCOL
      HttpResponse(StatusCodes.OK, entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        "<html><body><h1>HELLO WORLD!</h1></body></html>"
      ))
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(StatusCodes.NotFound, entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
        "<html><body><h1>Resource not found</h1></body></html>"
      ))
  }

  //Once we have the function to deal with the requests, we can plug it so it has effect
  val httpSinkConnectionHandler = Sink.foreach[IncomingConnection](conn => conn.handleWithSyncHandler(requestHandler))
  Http().bind("localhost", 8081).runWith(httpSinkConnectionHandler)
  // There is also a shorter version of this
  //Http().bindAndHandleSync(requestHandler, "localhost", 8082)

  /**
    * Method 2: serve back http responses asynchronously with futures
    */
  val asyncRequestHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, Uri.Path("/home"), _, _, _) => Future {
      HttpResponse(StatusCodes.OK, entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        "<html><body><h1>HELLO WORLD!</h1></body></html>"
      ))
    }
    case request: HttpRequest =>
      request.discardEntityBytes()
      Future {
        HttpResponse(StatusCodes.NotFound, entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
          "<html><body><h1>Resource not found</h1></body></html>"
        ))
      }
  }

  Http().bindAndHandleAsync(asyncRequestHandler, "localhost", 8084)

  /**
    * Metrhod 3: Async via Akka streams
    */
  val streamsBasedRequestHandler: Flow[HttpRequest, HttpResponse, _] = Flow[HttpRequest].map {
    case HttpRequest(HttpMethods.GET, Uri.Path("/home"), _, _, _) =>
      HttpResponse(StatusCodes.OK, entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        "<html><body><h1>HELLO WORLD WITH STREAMS!</h1></body></html>"
      ))
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(StatusCodes.NotFound, entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
        "<html><body><h1>Resource not found</h1></body></html>"
      ))
  }
  // To use the above flow, we need to use the following
  Http().bind("localhost", 8085)//source of incoming connection
    .runForeach(conn=> conn.handleWith(streamsBasedRequestHandler))
  // The shorthand for the above is
  Http().bindAndHandle(streamsBasedRequestHandler, "localhost", 8086)

  // Exercise: create your own http server running on localhost:8388 which replies with:
  // welcome message on '/'
  // with a proper html on /about
  // '/search' redirects to google
  // otherwise 404

  val handler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, Uri.Path("/"),_ , _, _) => Future(HttpResponse(entity =
      HttpEntity(ContentTypes.`text/html(UTF-8)`, "<html><body>Welcome!</body></html>"))) // status code is ok by default
    case HttpRequest(HttpMethods.GET, Uri.Path("/about"),_ , _, _) => Future(HttpResponse(StatusCodes.OK, entity =
      HttpEntity(ContentTypes.`text/html(UTF-8)`, "<html><body>ABOUT</body></html>")))
    case HttpRequest(HttpMethods.GET, Uri.Path("/search"),_ , _, _) => Future(HttpResponse(StatusCodes.Found,
      headers = List(Location("http://www.google.com")),
      entity = HttpEntity(ContentTypes.`text/html(UTF-8)`, "<html><body>ABOUT</body></html>")))
    case request:HttpRequest =>
      request.discardEntityBytes()
      Future(HttpResponse(StatusCodes.NotFound, entity =
      HttpEntity(ContentTypes.`text/html(UTF-8)`, "<html><body>Not Found</body></html>")))
  }

  val bindingFuture = Http().bindAndHandleAsync(handler, "localhost", 8388)
  // The bind and handle returns the binding future seeing before, so we can shut it down
  bindingFuture.flatMap(binding => binding.unbind()).onComplete(_ => system.terminate())

}
