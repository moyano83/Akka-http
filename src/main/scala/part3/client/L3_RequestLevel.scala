package part3.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import spray.json._

import scala.util.{Failure, Success}

object L3_RequestLevel extends App with PaymentJsonProtocol {
  implicit val system = ActorSystem("RequestLevelAPI")
  implicit val materializer = ActorMaterializer()

  import system.dispatcher

  // This is the highest of the 3 levels of the akka http client and the simplest, it is build on top of the Host level api
  //Replies directly with a Future[HttpResponse]
  val responseFuture = Http().singleRequest(HttpRequest(uri = Uri("http://www.google.com"))).onComplete {
    case Success(response) =>
      // We also need to discard the response bytes!!!!
      response.discardEntityBytes()
      println(s"The request was successful and returned ${response}")
    case Failure(ex) => println(s"The request failed with ${ex.getMessage}")
  }

  import PaymentSystemDomain._

  val creditCards = List(
    CreditCard("4242-4242-4242-4242", "424", "tx-test-account"),
    CreditCard("1234-1234-1234-1234", "123", "tx-jorge-account"),
    CreditCard("1234-1234-4242-4242", "321", "tx-other-account")
  )
  // For each of the above credit cards I want to create a request to the pyament system
  val paymentRequests = creditCards.map(cc => PaymentRequest(cc, "myOnlineStore", 10))

  val paymentHttpRequests = paymentRequests.map { pr =>
    HttpRequest(HttpMethods.POST,
      uri = Uri("http://localhost:8080/api/payments"), // In this case we need the absolute uri of the payments system
      entity = HttpEntity(ContentTypes.`application/json`, pr.toJson.prettyPrint))
  }

  // Example of integration with the payment system
  Source(paymentHttpRequests)
    .mapAsync(2)(request => Http().singleRequest(request))
    .runForeach(println)

}
