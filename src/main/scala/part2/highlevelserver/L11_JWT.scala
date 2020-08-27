package part2.highlevelserver


import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.headers.RawHeader
import akka.http.scaladsl.model.{HttpResponse, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.util.Timeout
import pdi.jwt.{JwtAlgorithm, JwtClaim, JwtSprayJson}
import spray.json._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.util.{Failure, Success}


object SecurityDomain extends DefaultJsonProtocol {

  case class LoginRequest(username: String, password: String)

  implicit val loginRequestFormat = jsonFormat2(LoginRequest)
}

object L11_JWT extends App with SprayJsonSupport {
  /**
    * JWT stands for JSON Web Token often used in ms and to control authorization, the flow is the following:
    * 1 - You authenticate to the server
    * 2 - The server sends you back a string aka token
    * 3 - Then this token is used for other secure endpoints within the authorization header, then the endpoint will check
    * for permissions associated to the token and then accept/reject the request
    *
    * A JWT string looks like this: xxxxx.yyyyy.zzzzz
    * - The first part (x's) is the header which is a plain JSON encoded in base 64
    * - The second (y's) is the payload (or claims in JWT therminology) contains register claims like the issuer or
    * expiration date and custom data like name, permissions, etc... This is also base 64 encoded
    * - The third part (z's) is the signature, the encoder will take the header and the payload, and will sign this with
    * an algorithm and encode it with a secret key, then the value is encoded in base 64
    */
  implicit val system = ActorSystem("JWTDemo")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(3 seconds)

  // The com.pauldijou.jwt-spray-json  is a jwt support library which will be used to create and decode JWT tokens

  import SecurityDomain._

  val algorithm = JwtAlgorithm.HS256
  val secretKey = "SomeSecretKeyThatShouldntBeInClearText"

  val secretPasswordDB = Map(
    "admin" -> "admin",
    "jorge" -> "pass")

  def checkPassword(username: String, pass: String): Boolean = secretPasswordDB.get(username).map(_ == pass).getOrElse(false)

  def createToken(username: String, days: Int): String = {
    val claims = JwtClaim(
      expiration = Some((System.currentTimeMillis()  / 1000) + TimeUnit.DAYS.toSeconds(1)), //epoc time for the token to expire
      issuedAt = Some(System.currentTimeMillis()  / 1000),
      issuer = Some("JorgeMoyano"),
    )
    JwtSprayJson.encode(claims, secretKey, algorithm) // This returns the JWT string
  }

  // The parameter with the algorithm is a Seq cause you pass a list of algorithms you want to try with the token
  def isTokenExpired(token: String): Boolean = JwtSprayJson.decode(token, secretKey, Seq(algorithm)) match{
    case Success(claims) => claims.expiration.getOrElse(0L) < (System.currentTimeMillis() / 1000)
    case Failure(_) => true
  }

  def isTokenValid(token: String): Boolean = JwtSprayJson.isValid(token, secretKey, Seq(algorithm))

  val loginRoute = post {
    entity(as[LoginRequest]) {
      case LoginRequest(username, password) if (checkPassword(username, password)) =>
        val token = createToken(username, 1)
        respondWithHeader(RawHeader("Access-Token", token)) {
          complete(StatusCodes.OK)
        }
      case _ =>
        complete(StatusCodes.Unauthorized)
    }
  }
  val authenticatedRoute = (path("secureEndpoint") & get) {
    optionalHeaderValueByName("Authorization") {
      case Some(token) =>
        if(isTokenValid(token)){
          if (isTokenExpired(token)) {
            complete(HttpResponse(StatusCodes.Unauthorized, entity = "The token is expired"))
          }else {
            complete("User access granted")
          }
        }else{
          complete(HttpResponse(StatusCodes.Unauthorized, entity = "The token is invalid"))
        }
      case _ => complete(HttpResponse(StatusCodes.Unauthorized, entity = "No Token provided"))
    }
  }

  val route = loginRoute ~ authenticatedRoute

  Http().bindAndHandle(route, "localhost", 8080)

}
