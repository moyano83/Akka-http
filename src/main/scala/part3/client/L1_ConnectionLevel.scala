package part3.client

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethod, HttpMethods, HttpRequest, HttpResponse, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import PaymentSystemDomain._
import spray.json._

import scala.util.{Failure, Success}


object L1_ConnectionLevel extends App with PaymentJsonProtocol {
  implicit val system = ActorSystem("ConnectionLevel")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  // This is a Flow from HttpRequest to HttpResponse which materialized value is a future of Http.OutgoingConnection
  // This API is most useful when you know you'll be constantly creating HTTP request to send it to another server
  // This is not really recommended for a single request
  val connectionFlow  = Http().outgoingConnection("www.google.com")

  def oneOfRequest(request:HttpRequest) =
    Source.single(request).via(connectionFlow).runWith(Sink.head)

  oneOfRequest(HttpRequest()).onComplete{
    case Success(response) => println(s"Response is: ${response}")
    case Failure(ex) => ex.printStackTrace()
  }

  /**
    * Imagine creating a small payment system for an online store that uses an payment provider like paypal (code is in
    * PaymentSystem.scala
    */
  val creditCards = List(
    CreditCard("4242-4242-4242-4242", "424", "tx-test-account"),
    CreditCard("1234-1234-1234-1234", "123", "tx-jorge-account"),
    CreditCard("1234-1234-4242-4242", "321", "tx-other-account")
  )
  // For each of the above credit cards I want to create a request to the pyament system
  val paymentRequests = creditCards.map(cc => PaymentRequest(cc, "myOnlineStore", 10))

  val paymentHttpRequests =  paymentRequests.map(pr =>
    HttpRequest(HttpMethods.POST,
      uri = Uri("/api/payments"),
      entity = HttpEntity(ContentTypes.`application/json`, pr.toJson.prettyPrint))
  )

  // With this requests we need to feed a flow to send it to the server
  Source(paymentHttpRequests)
    .via(Http().outgoingConnection("localhost", 8080))
    .runWith(Sink.foreach[HttpResponse](println))
  // The result of the above is
  //HttpResponse(200 OK,List(Server: akka-http/10.1.7, Date: Fri, 28 Aug 2020 11:20:33 GMT),
  //    HttpEntity.Strict(text/plain; charset=UTF-8,OK),HttpProtocol(HTTP/1.1))
  //HttpResponse(403 Forbidden,List(Server: akka-http/10.1.7, Date: Fri, 28 Aug 2020 11:20:33 GMT),
  //    HttpEntity.Strict(text/plain; charset=UTF-8,The request was a legal request, but the server is refusing to respond to it.),HttpProtocol(HTTP/1.1))
  //HttpResponse(200 OK,List(Server: akka-http/10.1.7, Date: Fri, 28 Aug 2020 11:20:33 GMT),
  //    HttpEntity.Strict(text/plain; charset=UTF-8,OK),HttpProtocol(HTTP/1.1))

  // For an HTTPS client you need to pass a security context and call the method outgoingConnectionHttps
}
