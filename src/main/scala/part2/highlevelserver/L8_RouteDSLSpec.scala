package part2.highlevelserver

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.model.{ContentTypes, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.MethodRejection
import akka.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.{Matchers, WordSpec}
import spray.json._

import scala.concurrent.Await
import scala.concurrent.duration._
/**
  * Write a small class to simulate a digitalized library
  */
case class Book(id: Int, author: String, title: String)


trait BookJsonProtocol extends DefaultJsonProtocol {
  implicit val BookFormat = jsonFormat3(Book)
}

  // First step to test our DSL is to mixin the traits ScalatestRouteTest
class L8_RouteDSLSpec extends WordSpec with Matchers with ScalatestRouteTest with BookJsonProtocol {

    import L8_RouteDSLSpec._
  "A digital library backend" should {
    "Return all the books in the library" in {
      // Send an http through the endpoint you want to test
      // inspect the response
      Get("/api/book") ~> routes ~> check { // This is the backbone of an http test
        // inside this block we have access to a lot of facilities like:
        status shouldBe StatusCodes.OK
        entityAs[List[Book]] shouldBe books
      }
    }
    "Return a book by id as parameter" in {
      Get("/api/book?id=1") ~> routes ~> check { // This is the backbone of an http test
        // inside this block we have access to a lot of facilities like:
        status shouldBe StatusCodes.OK
        responseAs[Option[Book]] shouldBe Some(Book(1, "Harper Lee", "To kill a Humimbird"))
      }
    }

    "Return a book by id in the route" in {
      Get("/api/book/1") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[Option[Book]] shouldBe Some(Book(1, "Harper Lee", "To kill a Humimbird"))
      }
    }

    "Return a book by calling the endpoint with the id in the path" in {
      Get("/api/book/1") ~> routes ~> check {
        response.status shouldBe StatusCodes.OK
        // You can parse the json message directly like this
        val strictEntityFuture = response.entity.toStrict(1 seconds)
        val entity = Await.result(strictEntityFuture, 1 second)
        entity.contentType shouldBe ContentTypes.`application/json`

        val book = entity.data.utf8String.parseJson.convertTo[Option[Book]]
        book shouldBe Some(Book(1, "Harper Lee", "To kill a Humimbird"))
      }
    }

    "insert a book in the DB" in {
      val book = Book(5, "Steven Pressfield", "The war of art")
      Post("/api/book", book) ~> routes ~> check {
        response.status shouldBe StatusCodes.OK
        books should contain(book)
      }
    }

    "not accept other methods than POST and GET" in {
      Delete("/api/book") ~> routes ~> check {
        rejections.isEmpty shouldBe false

        val methodRejections = rejections.collect {
          case rejection: MethodRejection => rejection
        }

        methodRejections.length shouldBe 2
      }
    }

    "return all the books of a given author" in {
      Get("/api/book/author/Harper%20Lee") ~> routes ~> check{
        status shouldBe StatusCodes.OK
        entityAs[List[Book]] shouldBe List(Book(1, "Harper Lee", "To kill a Humimbird") )
      }
    }
  }
}

object L8_RouteDSLSpec extends BookJsonProtocol with SprayJsonSupport {
  // code under test
  var books = List(
    Book(1, "Harper Lee", "To kill a Humimbird"),
    Book(2, "J.R.R Tolkien", "The Lord of the Rings"),
    Book(3, "J.R.R Martin", "Songs of Ice and Fire"),
    Book(4, "Tony Robbins", "Awake The Giant Whithin")
  )

  /**
    * Get /api/book -> Gets all books
    * Get /api/book/{id} -> get book with id X
    * Get /api/book?id=X -> get book with id X
    * Post /api/book -> Add  a book
    */

  val routes = pathPrefix("api" / "book") {
    (path("author" / Segment) & get) { author =>
      complete(books.filter(_.author == author))
    } ~
      get {
        (path(IntNumber) | parameter('id.as[Int])) { id =>
          complete(books.find(_.id == id))
        } ~
          pathEndOrSingleSlash {
            complete(books)
          }
      } ~
      post {
        entity(as[Book]) { book =>
          books = books :+ book
          complete(StatusCodes.OK)
        } ~
          complete(StatusCodes.BadRequest)
      }
  }
}

