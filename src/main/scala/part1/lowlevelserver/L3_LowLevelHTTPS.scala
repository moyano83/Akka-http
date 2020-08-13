package part1.lowlevelserver

import java.io.InputStream
import java.security.{KeyStore, SecureRandom}

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpMethods, HttpRequest, HttpResponse, StatusCodes}
import akka.http.scaladsl.{ConnectionContext, Http, HttpsConnectionContext}
import akka.stream.ActorMaterializer
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}
import part1.lowlevelserver.L3_LowLevelHTTPS.getClass

object HTTPSContext{
  // First we load the keystore
  // Under resources there is a keystore.pkcs12 file that contains certificates
  // You'll need a certificate signed with a certificate authority if you want to put your server online
  val ks = KeyStore.getInstance("PKCS12")
  val keyStoreFile: InputStream = getClass.getClassLoader.getResourceAsStream("keystore.pkcs12")

  // Step 1: We need to initialize the keystore with the keystoreFile and the password
  val password = "akka-https".toCharArray // just for example, never store clearly
  ks.load(keyStoreFile, password)

  // Step 2: Initialize a key manager. A key Manager manages https certificates within a keystore
  // SunX509 is the format of the certificates based in the so called X509 public key infrastructure which is a known
  // standard
  val keyManagerFactory = KeyManagerFactory.getInstance("SunX509")
  keyManagerFactory.init(ks, password)

  // Step 3: Initialize the trust manager. A trust manager manages who signed the certificates managed by the key manager
  val trustManagerFactory = TrustManagerFactory.getInstance("SunX509")
  trustManagerFactory.init(ks)

  // Step 4: Initialize an SSL context
  val sslContext = SSLContext.getInstance("TLS") //Transport Layer Security
  sslContext.init(keyManagerFactory.getKeyManagers, trustManagerFactory.getTrustManagers, new SecureRandom())

  // Step 5: Return the https connection context
  // This is the instance that we care about when initializing an akka https server
  val httpsConnectionContext: HttpsConnectionContext = ConnectionContext.https(sslContext)
}

object L3_LowLevelHTTPS extends App {
  implicit val system = ActorSystem("LowLevelHTTPS")
  implicit val materializer = ActorMaterializer()



  // Now we initialize the akka https server, the request handler is just for ilustration purposes
  val requestHandler: HttpRequest => HttpResponse = {
    case HttpRequest(HttpMethods.GET, _, _, _, _) =>
      HttpResponse(StatusCodes.OK, entity = HttpEntity(
        ContentTypes.`text/html(UTF-8)`,
        "<html><body><h1>HELLO WORLD!</h1></body></html>"
      ))
    case request: HttpRequest =>
      request.discardEntityBytes()
      HttpResponse(StatusCodes.NotFound, entity = HttpEntity(ContentTypes.`text/html(UTF-8)`,
        "<html><body><h1>Resource not found</h1></body></html>"
      ))
  }

  val bind = Http().bindAndHandleSync(requestHandler, "localhost", 8443, HTTPSContext.httpsConnectionContext)
}
