package com.oschrenk.expando

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Cookie, Location, `Set-Cookie`}
import akka.stream.{ActorMaterializer, Materializer}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.io.Source

object Expando {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    // akka http does not follow redirects automatically
    // see https://github.com/akka/akka-http/issues/195
    def redirectOrResult(originalUrl: Uri, newLocation: Option[Uri], response: HttpResponse)(implicit materializer: Materializer): Future[Uri] = {
      response.status match {
        case StatusCodes.Found | StatusCodes.MovedPermanently | StatusCodes.SeeOther | StatusCodes.TemporaryRedirect ⇒
          response.discardEntityBytes()

          val cookies = response.header[`Set-Cookie`].map(_.cookie).map(c => Cookie(c.name, c.value))
          val newUri = response.header[Location] match {
            case Some(location) if location.uri.isRelative =>
              newLocation.getOrElse(originalUrl).withPath(location.uri.path)
            case Some(location)  =>
              location.uri
            case None =>
              throw new IllegalArgumentException("empty location header on redirect")
          }

          // change to GET method as allowed by https://tools.ietf.org/html/rfc7231#section-6.4.3
          Http().singleRequest(HttpRequest(method = HttpMethods.GET, uri = newUri, headers = cookies.toList))
            .flatMap(res => redirectOrResult(originalUrl, Some(newUri), res))
        case _ ⇒
          response.discardEntityBytes()
          Future.successful(newLocation.getOrElse(originalUrl))
      }
    }

    val urls = Source.fromFile("/Users/oliver/Downloads/urls.txt", "UTF-8").getLines()
    val result = Future.sequence{
      urls.map { url =>
        Http().singleRequest(HttpRequest(uri = url))
          .flatMap(res => redirectOrResult(url, None, res).map{ newLoc =>
            println(s"$url $newLoc")
          }).recover{
          case err =>
            println(s"$url $err")
        }
      }
    }

    Await.result(result, Duration.Inf).foreach{
      _ => system.terminate()
    }
  }

}
