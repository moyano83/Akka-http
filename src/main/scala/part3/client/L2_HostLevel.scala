package part3.client

import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, Uri}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import spray.json._

import scala.util.{Failure, Success, Try}

object L2_HostLevel extends App with PaymentJsonProtocol {

  // The host level api is an intermediate level api which has some benefits:
  // Provides a connection pool to a given host and ports combinations so we don't have to manage individual connections
  // Individual requests can reuse this connections transparently, this is recommended for high volume low latency
  // connections
  // It also adds the ability to attach data to requests (aside from payloads)
  implicit val system = ActorSystem("HostLevel")
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  // The host level api is shown below. The type of this pool is
  // Flow[(HttpRequest, Int), (Try[HttpResponse], Int), Http.HostConnectionPool]
  // The first element is a pair from the http request and the type provided
  // Then the output type is the try of response with the type provided
  // And the materialized value is the Http.HostConnectionPool
  // The reason why we have this type is because now that we don't manage individual connections, the connections are
  // stored in a cached pool and reused. What this means is that the HTTP requests that you might want to send through
  // this pool will go through any of the available connections and as such you may receive HTTP responses in a different
  // order than the original order of the requests.
  val poolFlow = Http().cachedHostConnectionPool[Int]("www.google.com")

  // The explanation to the above can be seen with this example:
  val flowRequests = Source(1 to 10).map(i => (HttpRequest(), i)).via(poolFlow).map{
    case (Success(response), value) =>
      // IMPORTANT: If I don't discard the response it will block the connection that this response wants to get
      // through. What this leads to is leaking connections from this connection pool.
      response.discardEntityBytes()
      s"Request [${value}] has received [${response}]"
    case (Failure(ex), value) => s"Request [${value}] has failed with exception [${ex.getMessage}]"
  }.to(Sink.foreach[String](println))

  //flowRequests.run()

  // If we inspect the results we can see that the responses are not ordered, that's why we can pass some data along the
  // request, so we can identify which request generated that response

  // We use this pool on the payment application
  import PaymentSystemDomain._
  val creditCards = List(
    CreditCard("4242-4242-4242-4242", "424", "tx-test-account"),
    CreditCard("1234-1234-1234-1234", "123", "tx-jorge-account"),
    CreditCard("1234-1234-4242-4242", "321", "tx-other-account")
  )
  // For each of the above credit cards I want to create a request to the pyament system
  val paymentRequests = creditCards.map(cc => PaymentRequest(cc, "myOnlineStore", 10))

  val paymentHttpRequests =  paymentRequests.map { pr =>
    (HttpRequest(HttpMethods.POST,
      uri = Uri("/api/payments"),
      entity = HttpEntity(ContentTypes.`application/json`, pr.toJson.prettyPrint)),
      UUID.randomUUID().toString)
  }

  Source(paymentHttpRequests)
    .via(Http().cachedHostConnectionPool[String]("localhost", 8080))
    .runForeach { // The element here is a (Try[HttpResponse],String)
      case (Success(response@HttpResponse(StatusCodes.Forbidden, _, _, _)), uuid) =>
        println(s"The order ${uuid} was not allowed to proceed ${response}")
      case (Success(response), uuid) =>
        // we can do something with the data attached to it, for example send an email, remove it from DB, notify a user...
        println(s"The order ${uuid} returned ${response}")
      case (Failure(ex), uuid) => println(s"The order ${uuid} failed. Returned ${ex.getMessage}")
    }

  // Long live requests can starve the pool, so the ideal case as previously mentioned is high volume low latency requests
  // Do not use with one off requests as it has to materialize the flow
}
