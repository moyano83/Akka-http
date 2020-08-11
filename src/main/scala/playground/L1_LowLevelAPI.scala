package playground

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.IncomingConnection
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Sink

import scala.util.{Failure, Success}

object L1_LowLevelAPI extends App {
  implicit val system = ActorSystem("LowLevelServerAPI")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  // This source is a source of incoming connections, the materialized value is of type Future[Http.ServerBinding], which
  // allows us to unbind or shutdown the server
  val serverSource = Http().bind("localhost", 8080)

  val connectionSink = Sink.foreach[IncomingConnection]{ connection =>
    println(s"Accepting incoming connection from ${connection.remoteAddress}")
  }

  // To start the server we need to get a materialized value from an server source to the connectionSink

  val serverBindingFuture = serverSource.to(connectionSink).run()

  serverBindingFuture.onComplete{
    case Success(value) => println("Server binding successful")
    case Failure(exception) => println(s"Server binding failure, ${exception}")
  }


}
