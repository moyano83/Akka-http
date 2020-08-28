package part3.client

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.http.scaladsl.server.Directives._
import part3.client.PaymentSystemDomain.PaymentRequest
import spray.json.DefaultJsonProtocol
import spray.json._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._

case class CreditCard(serialNumber:String, securityCode:String, account:String)

object PaymentSystemDomain{
  case class PaymentRequest(creditCard: CreditCard, receiverAccount:String, amount:Double)
  case object PaymentAccepted
  case object PaymentRejected
}

class PaymentValidator extends Actor with ActorLogging{
  import PaymentSystemDomain._
  override def receive: Receive = {
    case PaymentRequest(CreditCard(serialNumber, _, account), receiver, amount) =>
      log.info(s"Sender account is trying to send ${amount} to ${receiver}")
      if(serialNumber == "1234-1234-1234-1234") sender() ! PaymentRejected
      else sender() ! PaymentAccepted
  }
}

trait PaymentJsonProtocol extends DefaultJsonProtocol{
  implicit val creditCardFormat = jsonFormat3(CreditCard)
  implicit val paymentRequestFormat = jsonFormat3(PaymentRequest)
}
/**
  * This simulates a WS for payment to be used in  the module
  */
object PaymentSystem extends App with PaymentJsonProtocol with SprayJsonSupport{
  implicit val system = ActorSystem("PaymentSystem")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(3 seconds)

  import system.dispatcher
  import PaymentSystemDomain._

  val paymentValidator = system.actorOf(Props[PaymentValidator], "paymentValidator")

  val paymentRoute = path("api" /"payments"){
    post{
      entity(as[PaymentRequest]){ paymentRequest =>
        val validationResponse = (paymentValidator ? paymentRequest).map{
          case PaymentRejected => StatusCodes.Forbidden
          case PaymentAccepted => StatusCodes.OK
          case _ => StatusCodes.BadRequest
        }
        complete(validationResponse)
      }
    }
  }

  // This can be tested from the command line with http POST localhost:8080/api/payments < src/main/json/paymentRequest.json
  // and invalid requests with http POST localhost:8080/api/payments < src/main/json/invalidPaymentRequest.json
  Http().bindAndHandle(paymentRoute, "localhost", 8080)
}
