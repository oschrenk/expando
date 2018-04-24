package com.oschrenk.expando

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, Materializer}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.io.Source
import scala.util.{Failure, Success}

object Expando {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    def redirectOrResult(originalUrl: Uri, newLocation: Option[Uri], response: HttpResponse)(implicit materializer: Materializer): Future[Uri] =
    response.status match {
      case StatusCodes.Found | StatusCodes.MovedPermanently | StatusCodes.SeeOther ⇒
        val location = response.header[Location]
        if (location.isEmpty) {
          println(s"${response.status} $originalUrl")
        }

        val newUri = location.get.uri
        response.discardEntityBytes()

        // change to GET method as allowed by https://tools.ietf.org/html/rfc7231#section-6.4.3
        Http().singleRequest(HttpRequest(method = HttpMethods.GET, uri = newUri))
          .flatMap(res => redirectOrResult(originalUrl, location.map(_.uri), res))
      // TODO: what to do on an error? Also report the original request/response?
      // TODO: also handle 307, which would require resending POST requests
      case _ ⇒
        response.discardEntityBytes()
        Future.successful(newLocation.getOrElse(originalUrl))
    }

    val urls = Source.fromFile("/Users/oliver/Downloads/urls.txt", "UTF-8").getLines().toSeq
    val result: Future[Seq[HttpResponse]] = Future.sequence{urls.map{ url =>
      // akka http does not follow redirects automatically
      // see https://github.com/akka/akka-http/issues/195
    val f = Http().singleRequest(HttpRequest(uri = url))
      f.onComplete {
        case Success(res) => redirectOrResult(url, None, res).map{ newLoc =>
          println(s"$url $newLoc")
        }
        case Failure(error) =>
          println(s"$url $error")
      }
      f
    }}

    Await.result(result, Duration.Inf)
    result.map(_ => system.terminate())
  }

}
