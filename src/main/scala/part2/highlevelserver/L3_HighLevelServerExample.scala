package part2.highlevelserver

import akka.actor.{ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import part1.lowlevelserver.GuitarDB._
import part1.lowlevelserver.{Guitar, GuitarDB, GuitarStoreJsonProtocol}
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

object L3_HighLevelServerExample extends App with GuitarStoreJsonProtocol {
  implicit val system = ActorSystem("HighLevelServerExample")
  implicit val materializer = ActorMaterializer()

  import akka.http.scaladsl.server.Directives._

  /**
    * Replicate the exercise on part 1 L2
    * - GET on localhost:8080/api/guitar => should return all guitars
    * - GET on localhost:8080/api/guitar?id=x => get the guitar with id x
    * - GET /api/guitar/inventory?inStock=true/false
    * - POST /api/guitar/inventory?id=X&quantity=Y
    */

  val guitarDBActor = system.actorOf(Props[GuitarDB])
  guitarDBActor ! CreateGuitar(Guitar("Fender", "Stratocaster", 1))
  guitarDBActor ! CreateGuitar(Guitar("Gibson", "Les paul", 1))
  guitarDBActor ! CreateGuitar(Guitar("Martin", "LX1", 0))

  implicit val timeout = Timeout(2 seconds)
  val guitarServerRoute = path("api" / "guitar") {
    // This is similar to the low level, always put the most specific route first
    parameter('id.as[Int]) { guitarId =>
      get {
        val guitarFuture: Future[Option[Guitar]] = (guitarDBActor ? FindGuitarByID(guitarId)).mapTo[Option[Guitar]]
        val entityFuture = guitarFuture.map { guitarOpt =>
          HttpEntity(ContentTypes.`application/json`, guitarOpt.toJson.prettyPrint)
        }
        complete(entityFuture)
      }
    } ~
      get {
        val guitarsFuture = (guitarDBActor ? FindAllGuitar).mapTo[List[Guitar]]
        val entityFuture = guitarsFuture.map { guitars =>
          HttpEntity(ContentTypes.`application/json`, guitars.toJson.prettyPrint)
        }
        complete(entityFuture)
      }
  } ~
    path("api" / "guitar" / IntNumber) { guitarId =>
      get {
        val guitarFuture: Future[Option[Guitar]] = (guitarDBActor ? FindGuitarByID(guitarId)).mapTo[Option[Guitar]]
        val entityFuture = guitarFuture.map(guitarOpt =>
          HttpEntity(ContentTypes.`application/json`, guitarOpt.toJson.prettyPrint))
        complete(entityFuture)
      }
    } ~
    path("api" / "guitar" / "inventory") {
      get {
        parameter('inStock.as[Boolean]) { inStock =>
          val guitarsFuture = (guitarDBActor ? FindAllGuitar).mapTo[List[Guitar]]
          val filteredGuitars = guitarsFuture.map { listGuitar =>
            listGuitar.filter(_.quantity > 0 == inStock)
          }
          val entityFuture = filteredGuitars.map { guitarList =>
            HttpEntity(ContentTypes.`application/json`, guitarList.toJson.prettyPrint)
          }
          complete(entityFuture)
        }
      }
    }

  def toHttpEntity(payload:String) = HttpEntity(ContentTypes.`application/json`, payload)
  // It is possible to simplify the above,  for that we use the pathPrefix, which is useful when a group of endpoints
  // starts with the same name
  val simplifiedGuitarServerRoute = (pathPrefix("api" / "guitar") & get) {
    path("inventory") {
      parameter('inStock.as[Boolean]) { inStock =>
        val guitarsFuture = (guitarDBActor ? FindAllGuitar).mapTo[List[Guitar]]
        val filteredGuitars = guitarsFuture.map { listGuitar =>
          listGuitar.filter(_.quantity > 0 == inStock)
        }
        val entityFuture = filteredGuitars.map { guitarList =>
          HttpEntity(ContentTypes.`application/json`, guitarList.toJson.prettyPrint)
        }
        complete(entityFuture)
      }
    } ~
      (path(IntNumber) | parameter('id.as[Int])){guitarId =>
        complete((guitarDBActor ? FindGuitarByID(guitarId))
          .mapTo[Option[Guitar]]
          .map(_.toJson.prettyPrint)
          .map(toHttpEntity))
      } ~
      pathEndOrSingleSlash {
        complete((guitarDBActor ? FindAllGuitar)
          .mapTo[List[Guitar]]
          .map(_.toJson.prettyPrint)
          .map(toHttpEntity))
      }
  }

  Http().bindAndHandle(simplifiedGuitarServerRoute, "localhost", 8080)
}