package part2.highlevelserver

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import spray.json.{DefaultJsonProtocol, _}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}

case class Person(pin: Int, name: String)

trait PersonJsonProtocol extends DefaultJsonProtocol {
  implicit val personFormat = jsonFormat2(Person)
}

object L4_HighLevelExercise extends App with PersonJsonProtocol {
  implicit val system = ActorSystem("HighLevelExercise")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(3 seconds)
  /**
    * Exercise: create a citizenship registration service which exposes the following endpoints:
    * GET /api/people Retrieves all people registered
    * GET /api/people/{pin} retrieves the person with that pin
    * GET /api/people?pin=xx retrieves the person with that pin
    * POST /api/people -d "JSON_PERSON"
    */
  var personDB: List[Person] = List[Person](
    Person(1, "Alice"),
    Person(2, "Bob"),
    Person(3, "Peter"),
    Person(4, "Phillip")
  )

  val route = pathPrefix("api" / "person") {
    get {
      (path(IntNumber) | parameter('pin.as[Int])) { pin =>
        val person = personDB.find(_.pin == pin)
        person match {
          case Some(person) => complete(HttpEntity(ContentTypes.`application/json`, person.toJson.prettyPrint))
          case None => complete(StatusCodes.NotFound)
        }
      } ~
        pathEndOrSingleSlash {
          complete(HttpEntity(ContentTypes.`application/json`, personDB.toJson.prettyPrint))
        }
    } ~
      (post & extractRequestEntity & extractLog) { (requestEntity, log) =>
          val strictEntity = requestEntity.toStrict(3 seconds)
          val personFuture = strictEntity.map(_.data.utf8String.parseJson.convertTo[Person])
        
          onComplete(personFuture){ // Directive to complete a future
            case Success(person) =>
              log.info(s"Received ${person}")
              val status = if (!personDB.contains(person)) {
                personDB = personDB :+ person
                StatusCodes.OK
              } else {
                StatusCodes.NotFound
              }
              log.info(s"Completed post with status ${status}")
              complete(status)
            case Failure(ex) => failWith(ex)
          }
      }
  }

  Http().bindAndHandle(route, "localhost", 8080)
}
