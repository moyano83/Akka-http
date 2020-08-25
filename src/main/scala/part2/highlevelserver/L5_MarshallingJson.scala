package part2.highlevelserver

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives.{parameter, _}
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.util.Timeout
import spray.json._
import scala.concurrent.ExecutionContext.Implicits._
import scala.concurrent.duration._

case class Player(nickName: String, characterClass: String, level: Int)


/**
  * Implement microservice for a massive multiplayer online game
  */
object GameAreaMap {

  case object GetAllPlayers

  case class GetPlayerByNickName(nickName: String)

  case class GetPlayersByCharacterClass(characterClass: String)

  case class AddPlayer(player: Player)

  case class RemovePlayer(player: Player)

  case object OperationSuccess

}

class GameAreaMap extends Actor with ActorLogging {

  import GameAreaMap._

  var players = Map[String, Player]()

  override def receive: Receive = {
    case GetAllPlayers =>
      log.info("Getting all players")
      sender() ! players.values.toList
    case GetPlayerByNickName(nickName) =>
      log.info(s"Getting player with nickname ${nickName}")
      sender() ! players.get(nickName)
    case GetPlayersByCharacterClass(characterClass) =>
      log.info(s"Getting player with character class ${characterClass}")
      sender() ! players.values.filter(_.characterClass == characterClass).toList
    case AddPlayer(player) =>
      log.info(s"Trying to add player ${player}")
      players = players + (player.nickName -> player)
      sender() ! OperationSuccess
    case RemovePlayer(player) =>
      log.info(s"Trying to remove player ${player}")
      players = players - player.nickName
      sender() ! OperationSuccess
  }
}

trait PlayerJsonProtocol extends DefaultJsonProtocol{
  implicit val playerFormat = jsonFormat3(Player)
}

object L5_MarshallingJson extends App with PlayerJsonProtocol with SprayJsonSupport{
  implicit val system = ActorSystem("MarshallingJson")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(3 seconds)

  import GameAreaMap._

  val gameMap = system.actorOf(Props[GameAreaMap], "gameMap")

  gameMap ! AddPlayer(Player("Martin Odersky", "Warrior", 70))
  gameMap ! AddPlayer(Player("Roland", "Elf", 60))
  gameMap ! AddPlayer(Player("Jorge", "Wizzard", 30))

  /**
    * Implement the following:
    * GET /api/player returns all players in the map
    * GET /api/player/{nickName} returns the player with the given NickName
    * GET /api/player?nickName=x returns the player with the given NickName
    * GET /api/player/class/{characterClass} returns the players with the given class
    * POST /api/player add the player to the map
    * DELETE /api/player remove the player from the map
    */

  // To make this route able to return case classes without having to serialize it, you need to:
  // 1 - import spray.json._
  // 2 - create a trait that extends from DefaultJsonProtocol
  // 3 - Mixin the trait with your app
  // 4 - Mixin SprayJsonSupport
  val route = pathPrefix("api" / "player") {
    get {
      path("class" / Segment) { characterClass =>
        val playersByClassFuture = (gameMap ? GetPlayersByCharacterClass(characterClass)).mapTo[List[Player]]
        complete(playersByClassFuture)
      } ~
        (path(Segment) | parameter('nickName)) { nickName =>
          val player = (gameMap ? GetPlayerByNickName(nickName)).mapTo[Option[Player]]
          complete(player)
        } ~
        pathEndOrSingleSlash {
          val players = (gameMap ? GetAllPlayers).mapTo[List[Player]]
          complete(players)
        }
    } ~
      post {
        // The as[X] here is also a directive with implicits to convert the HttpRequest to the given type
        entity(as[Player]){ player =>
          complete((gameMap ? AddPlayer(player)).map(_ => StatusCodes.OK))
        }
      } ~
      delete {
        entity(as[Player]){ player =>
          complete((gameMap ? RemovePlayer(player)).map(_ => StatusCodes.OK))
        }
      }
  }

  Http().bindAndHandle(route, "localhost", 8080)
}
