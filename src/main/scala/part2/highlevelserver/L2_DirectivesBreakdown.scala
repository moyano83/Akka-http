package part2.highlevelserver

import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpRequest, StatusCodes}
import akka.stream.ActorMaterializer

object L2_DirectivesBreakdown extends App {
  implicit val system = ActorSystem("DirectivesBreakdown")
  implicit val materializer = ActorMaterializer()

  import akka.http.scaladsl.server.Directives._

  /**
    * Directive type 1: Filter directives
    */
  // There are equivalent directives for get, post, delete, head, put, patch, options
  val simpleHttpMethodRoute = post {
    complete(StatusCodes.Forbidden)
  }
  // Example of simple path
  val simplePathRoute = path("about") {
    complete(HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
      """
        |<html>
        | <body>
        |   <h1>Hello from the about page</h1>
        | </body>
        |</html>
        |""".stripMargin))
  }

  // Example of complex path
  val complexPathRoute = path("api" / "myEndpoint") {
    complete(StatusCodes.OK)
  }
  //The below is not the same than this one, which can be accessed by the url:
  // http://localhost:8080/api%2FmyEndpoint
  // This is because '/' is urlEncoded, which doesn't happen in the example above
  val dontConfuseTheAboveWithThis = path("api/myEndpoint") {
    complete(StatusCodes.OK)
  }
  // The one below would match http://localhost:8080 and http://localhost:8080/
  val pathEndRoute = pathEndOrSingleSlash {
    complete(StatusCodes.OK)
  }

  /**
    * Directive type 2: Extraction directives
    */
  // Imagine you want to intercept a route like Get on /api/item/{number here}
  // You can use IntNumber extractor, and when you use it, the directive then expects a function between a number and a
  // directive
  val pathExtractionRoute = path("api" / "item" / IntNumber) { (id: Int) => {
    println(s"I have got a number ${id} in my path")
    complete(StatusCodes.OK)
  }
  }
  // The same works with multiple variables (Segment is used for strings)
  val pathMultiExtractionRoute = path("api" / Segment / IntNumber) { (category, id) => {
    println(s"I have got a category ${category} and a number ${id} in my path")
    complete(StatusCodes.OK)
  }
  }

  // Example of extracting query parameters (api/item?id=xx&category=yy)
  val queryParamExtractionRoute = path("api" / "item") {
    // The parameter type is a string but can be casted using the below. The directive "parameter" also exists if there is
    // a single parameter. A good practice is to pass the parameter names as symbols instead of strings (using the single
    // quote at the beginning of the name like 'id. Symbols are intern (held in a special memory place) in the JVM, and
    // they offer performance benefits because they are compared by reference instead of content equality.
    parameters("id".as[Int], "category") { (id: Int, category: String) =>
      println(s"I have got a category ${category} and a number ${id} in my path")
      complete(StatusCodes.OK)
    }
  }

  // This is to extract the request that gets to this endpoint
  val extractRequestRoute = path("control") {
    extractRequest { (request: HttpRequest) => // There are many extractors, type 'extract' and see the autocompletion options
      println(s"I've got the request ${request}")
      complete(StatusCodes.OK)
    }
  }

  /**
    * Directive type 3: Composite directives (The directives can get very complex, so there are options to group them
    * together)
    */
  val simpleNestedRoute = path("api" / "other") {
    get {
      complete(StatusCodes.OK)
    }
  }
  // A way to rewrite the above is shown below, we can join directives using the & operator
  val compactSimpleNestedRoute = (path("api" / "other") & get) {
    complete(StatusCodes.OK)
  }

  // This can be used with extraction directives like in
  val compactExtractNestedRoute = (path("api" / "control") & get & extractRequest & extractLog) {
    // The extract directives are extraction functions so their function parameters would pile up in the extraction directive
    (request: HttpRequest, log: LoggingAdapter) => {
      log.info(s"I have received ${request}")
      complete(StatusCodes.OK)
    }
  }

  // You can add different paths as well like /about and /info
  val repeatedRoute = path("about") {
    complete(StatusCodes.OK)
  } ~ path("info") {
    complete(StatusCodes.OK)
  }
  // The above can be simplified
  val nonRepeatedRoute = (path("about") | path("info")){
    complete(StatusCodes.OK)
  }

  // You can also do an or on extraction directives as well on certain conditions
  // Imagine you want to extract yourblog.com/42 and yourblog.com?postId=42
  val blogByIdRoute = path(IntNumber) {(blogId) => // This is equal to /42
    println(s"Blog post ${blogId}")
    complete(StatusCodes.OK)
  }
  val blogByQueryIdRoute = parameter("blogPostId".as[Int]) {(blogPostId) =>
    println(s"Blog post ${blogPostId}")
    complete(StatusCodes.OK)
  }

  // We can combine both routes like below, but the catch here is that the expressions must extract the same type and
  // number of values
  val combinedBlogByIdRoute = (path(IntNumber) | parameter("blogPostId".as[Int])){ (blogPostId) =>
    println(s"Blog post ${blogPostId}")
    complete(StatusCodes.OK)
  }

  /**
    * Directive type 4: 'Actionable' directives
    */
  // An example of actionable directive is the complete directive
  // The parameter passed here is a ToResponseMarshallable which represents anything that can be converted to an HttpResponse
  val completeOkRoute = complete(StatusCodes.OK)

  // Fail with throws an internal exception and completes the request with an HTTP 500
  val notSupportedPath = failWith(new RuntimeException("Unsupported!"))

  // Reject means handing it over to the next possible handler in the route tree
  val routeWithRejection = path("home") {  // home is rejected and then tries with the index path
    reject
  } ~ path("index"){
    completeOkRoute // We can reuse previous logic
  }

  /**
    * Exercise: Spot the mistake
    */
  val getOrPutPath = path("api" / "endpoint"){
    get {
      completeOkRoute
    }
    post {
      complete(StatusCodes.Forbidden)
    }
  }
  // Solution: Paths are not connectedby ~ so it only work for the post
}
