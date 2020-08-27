package part2.highlevelserver


import java.io.File

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, Multipart, StatusCodes}
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{FileIO, Sink, Source}
import akka.util.{ByteString, Timeout}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object L10_UploadingFiles extends App {
  implicit val system = ActorSystem("UploadingFiles")
  implicit val materializer = ActorMaterializer()
  implicit val timeout = Timeout(3 seconds)


  val filesRoute = (pathEndOrSingleSlash & get) {
    complete(HttpEntity(
      ContentTypes.`text/html(UTF-8)`,
      """
        |<html>
        | <body>
        |   <form action="http://localhost:8080/upload" method="POST" enctype="multipart/form-data">
        |     <input type="file" name="myFile">
        |       <button type="submit">Upload</button>
        |     </input>
        |   </form>
        | </body>
        |</html>
        |""".stripMargin
    ))
  } ~
    (path("upload") & extractLog) { logger =>
      // let's handle the file uploading. HTTP requests sends special http entities called multipart/form-data, for that we
      // use the entity directive
      entity(as[Multipart.FormData]) { formData => // This will contain the file payload
        // The File will be handled like a Source with all the chunks that the file might contain
        val partSource: Source[Multipart.FormData.BodyPart, Any] = formData.parts
        // The Future[Done] is only useful to know when the stream has actually finished
        val filePartsSink: Sink[Multipart.FormData.BodyPart, Future[Done]] = Sink.foreach[Multipart.FormData.BodyPart] { part =>
          if (part.name == "myFile") {
            // Create a file and save the parts to it
            val fileName = "target/" + part.filename.getOrElse(s"tempFile-${System.currentTimeMillis()}")
            val file = new File(fileName)
            logger.info(s"Writting to file ${fileName}")

            val fileContentSources: Source[ByteString, _] = part.entity.dataBytes // This retrieves a source of ByteStream
            //The IO stream package offers a bunch of utilities for stream based writing to files
            val fileContentSink: Sink[ByteString, _] = FileIO.toPath(file.toPath)

            fileContentSources.runWith(fileContentSink) // Dump the content of the body file to the file
          }
        }
        // Now we have to run an akka stream from the partSource to the filePartsSink and process the incoming data
        onComplete(partSource.runWith(filePartsSink)) {
          case Success(_) => complete("File Uploaded")
          case Failure(ex) => complete(StatusCodes.InternalServerError, s"File upload failed: ${ex.getMessage}")
        }
      }
    }

  Http().bindAndHandle(filesRoute, "localhost", 8080)

}
