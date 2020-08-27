package part2.highlevelserver


import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.ExceptionHandler
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.util.{CompactByteString, Timeout}
import part2.highlevelserver.L7_HandlingExceptions.simpleRouteWithExceptionHandlers

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
object L9_WebSocketsDemo extends App{
  implicit val system = ActorSystem("WebSocketsDemo")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(3 seconds)

  // Message is the class that models an exchange between websockets in akka, there is TextMessage and BinaryMessage
  // These messages are wrappers over sources because the messages that can be exchanged in both ways
  val textMessage = TextMessage(Source.single("Hello from a web socket"))// This is a wrapper over a source
  // the text is turned into bytes here
  val binaryMessage = BinaryMessage(Source.single(CompactByteString("Hello from a binary source")))

  val html =
    """
      |<html>
      |    <head>
      |        <script>
      |            var exampleSocket = new WebSocket("ws://localhost:8080/greeter");
      |            console.log("starting websocket...");
      |
      |            exampleSocket.onmessage = function(event) {
      |                var newChild = document.createElement("div");
      |                newChild.innerText = event.data;
      |                document.getElementById("1").appendChild(newChild);
      |            };
      |
      |            exampleSocket.onopen = function(event) {
      |                exampleSocket.send("socket seems to be open...");
      |            };
      |
      |            exampleSocket.send("socket says: hello, server!");
      |        </script>
      |    </head>
      |
      |    <body>
      |        Starting websocket...
      |        <div id="1">
      |        </div>
      |    </body>
      |
      |</html>
      |""".stripMargin

  def webSocketFlow:Flow[Message, Message, Any]  = Flow[Message].map{
    case msg:TextMessage => TextMessage(Source.single("Server Say back:") ++ msg.textStream ++ Source.single("!"))
    case msg:BinaryMessage =>
      msg.dataStream.runWith(Sink.ignore) // This flushes the binary message to not leak resources
      TextMessage(Source.single("Server received a binary message"))

  }
  // when the browser is open at localhost:8080, then we send the html content above, which will open a web socket connection
  // on localhost:8080/greeter
  val webSocketRoute = (pathEndOrSingleSlash & get){
    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, html))
  // When this path below is accessed, the server would handle the flow of messages with the webSocketFlow
  } ~ path("greeter") {
    handleWebSocketMessages(webSocketFlow) // This takes a flow from message to message
  }

  //Http().bindAndHandle(webSocketRoute, "localhost", 8080)

  // Imagine a feed on a social site
  case class SocialPost(owner:String, content:String)
  val socialFeed = Source(
    List(
      SocialPost("Martin", "Scala 3 has been announced!"),
      SocialPost("Jorge", "I am finishing the http akka course"),
      SocialPost("Martin", "I killed Java")
    )
  )

  val socialMessage = socialFeed
    .throttle(1, 2 seconds)
    .map(socialPost => TextMessage(s"${socialPost.owner} said ${socialPost.content}"))

  val socialFlow: Flow[Message, Message, Any] = Flow.fromSinkAndSource[Message, Message](
    Sink.foreach[Message](println),
    socialMessage)

  val webSocketRoute2 = (pathEndOrSingleSlash & get){
    complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, html))

  } ~ path("greeter") {
    handleWebSocketMessages(socialFlow)
  }

  Http().bindAndHandle(webSocketRoute2, "localhost", 8080)

}
