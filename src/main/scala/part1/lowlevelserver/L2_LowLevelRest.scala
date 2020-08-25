package part1.lowlevelserver

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.Uri.Query
import akka.http.scaladsl.model._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._

case class Guitar(name: String, model: String, quantity:Int)

object GuitarDB {

  case class CreateGuitar(guitar: Guitar)

  case class UpdateGuitar(id:Int, guitar: Guitar)

  case class GuitarCreated(id: Int)

  case class FindGuitarByID(id: Int)

  case object FindAllGuitar

}

class GuitarDB extends Actor with ActorLogging {

  import GuitarDB._

  var guitars = Map[Int, Guitar]()
  var currentGuitarID = 0

  override def receive: Receive = {
    case FindAllGuitar => log.info("Findig all guitars")
      sender() ! guitars.values.toList
    case FindGuitarByID(id) => log.info(s"Finding guitar with id ${id}")
      sender() ! guitars.get(id)
    case CreateGuitar(guitar) => log.info(s"Creating guitar ${guitar}")
      guitars = guitars + (currentGuitarID -> guitar)
      sender() ! currentGuitarID
      currentGuitarID = currentGuitarID + 1
    case UpdateGuitar(id, guitar) => log.info(s"Updating guitar ${guitar}")
      guitars = (guitars - id) + (currentGuitarID -> guitar)
      sender() ! currentGuitarID
  }
}

trait GuitarStoreJsonProtocol extends DefaultJsonProtocol {
  implicit val guitarFormat = jsonFormat3(Guitar)
}

object L2_LowLevelRest extends App with GuitarStoreJsonProtocol {
  implicit val system = ActorSystem("LowLevelRest")
  implicit val materializer = ActorMaterializer()

  import GuitarDB._

  implicit val timeout = Timeout(3 seconds)
  /**
    * We are going to use the GuitarDB actor to send responses to an H TTP client through the age to be server.
    * 1 - GET on localhost:8080/api/guitar => should return all guitars
    * 2 - POST on localhost:8080/api/guitar => insert guitar into the store
    * 3 - GET on localhost:8080/api/guitar?id=x => get the guitar with id x
    * We need to serialize the guitars to a format the client will understand, we'll use JSON (this is called marshalling)
    */
  // First we need to import spray.json._
  // Then define a Trait extending with DefaultJsonProtocol
  // Then define an implicit val inside the trait => yourClassFormat = JsonFormatX(CaseClass) where X is the number of
  // constructor parameters
  // Last mix this trait in your App

  val simpleGuitar = Guitar("Fender", "Stratocaster",1)
  println(simpleGuitar.toJson.prettyPrint) // This comes from the implicit contained in the DefaultJsonProtocol

  // To unmarshall, we do the following:
  val fenderString =
    """{
      |  "model": "Stratocaster",
      |  "name": "Fender",
      |  "quantity": 1
      |}""".stripMargin

  val fenderGuitar = fenderString.parseJson.convertTo[Guitar]

  val lowLevelGuitarDB = system.actorOf(Props[GuitarDB])
  lowLevelGuitarDB ! CreateGuitar(fenderGuitar)
  lowLevelGuitarDB ! CreateGuitar(Guitar("Gibson", "Les paul", 1))
  lowLevelGuitarDB ! CreateGuitar(Guitar("Martin", "LX1", 0))

  // Method to produce an HttpResponse with a guitar from query parameters
  def getGuitar(query: Query): Future[HttpResponse] = {
    val guitarId = query.get("id").map(_.toInt)

    guitarId match {
      case None => Future(HttpResponse(StatusCodes.NotFound))
      case Some(id) =>
        val guitarFuture: Future[Option[Guitar]] = (lowLevelGuitarDB ? FindGuitarByID(id)).mapTo[Option[Guitar]]
        guitarFuture.map {
            case None => HttpResponse(StatusCodes.NotFound)
            case Some(guitar) => HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, guitar.toJson.prettyPrint))
        }
    }
  }

  // We need now to integrate this marshall/unmarshall logic into our web app
  // As we are interacting with actors and DB, we need to use futures in our handlers
  val requestHandler: HttpRequest => Future[HttpResponse] = {
    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar"), _, _, _) =>
      // Add the query parameter handling here
      val queryParams = uri.query() // this is a Query object, kind of a map
      if (queryParams.isEmpty) {
        val guitarsFuture = (lowLevelGuitarDB ? FindAllGuitar).mapTo[List[Guitar]]
        guitarsFuture.map { guitars =>
          HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, guitars.toJson.prettyPrint))
        }
      } else {
        //  If there is parameters we retrieve the guitar
        getGuitar(queryParams)
      }
    //entities are a source of ByteStream which can cause problems. We can use a method called toStrict to
    // create a future which will contain the entire entity contents (which returns a future as we don't know how long is
    // going to take to get all the entity). We extract the string by calling data.utf8String on the StrictEntity instance
    // that we parse into our case class
    case HttpRequest(HttpMethods.POST, Uri.Path("/api/guitar"), _, entity, _) =>
      val strictEntity = entity.toStrict(3 seconds)
      strictEntity.flatMap(strictEntity => {
        val guitar = strictEntity.data.utf8String.parseJson.convertTo[Guitar]
        val guitarCreatedFuture = (lowLevelGuitarDB ? CreateGuitar(guitar)).mapTo[GuitarCreated]

        guitarCreatedFuture.map(_ => HttpResponse(StatusCodes.OK))
      })

    case HttpRequest(HttpMethods.GET, uri@Uri.Path("/api/guitar/inventory"), _, d, _) =>
      val inStock = uri.query().get("inStock").map(_.toBoolean).getOrElse(true)
      val guitarsFuture = (lowLevelGuitarDB ? FindAllGuitar).mapTo[List[Guitar]]
      guitarsFuture.map(list => list.filter(guitar=> (guitar.quantity!=0) == inStock)).map { guitars =>
        HttpResponse(StatusCodes.OK, entity = HttpEntity(ContentTypes.`application/json`, guitars.toJson.prettyPrint))
      }
    case HttpRequest(HttpMethods.POST, uri@Uri.Path("/api/guitar/inventory"), _, enteity, _) =>
      println("Received request to add to inventory")
      val idOpt = uri.query().get("id").map(_.toInt)
      val quantityOpt = uri.query().get("quantity").map(_.toInt)
      idOpt match{
        case None => Future(HttpResponse(StatusCodes.NotFound))
        case Some(id) =>
        val guitarsFuture = (lowLevelGuitarDB ? FindGuitarByID(id)).mapTo[Option[Guitar]]
          guitarsFuture.map{
            case None => HttpResponse(StatusCodes.NotFound)
            case Some(guitar) => {
              quantityOpt match {
                case None => HttpResponse(StatusCodes.NotFound)
                case Some(quantity) =>
                  lowLevelGuitarDB ! UpdateGuitar(id, Guitar(guitar.name, guitar.model, guitar.quantity + quantity))
                  HttpResponse(StatusCodes.OK)
              }
            }
          }
      }
    // It is very important to deal with the general case because if you don't reply to an existing request
    // that will be interpreted as back pressure that back pressure is interpreted by the streams based aka
    // HTTP server and that will be propagated all the way down to the TCP layer, so all subsequent HTTP requests will become
    // slower and slower.
    case request: HttpRequest => request.discardEntityBytes()
      Future(HttpResponse(StatusCodes.NotFound))
  }

  Http().bindAndHandleAsync(requestHandler, "localhost", 8080)

  /**
    * Exercise: enhance the guitar case class with a quantity field, by default 0. Add:
    * - GET /api/guitar/inventory?inStock=true/false
    * - POST /api/guitar/inventory?id=X&quantity=Y
    */

}
