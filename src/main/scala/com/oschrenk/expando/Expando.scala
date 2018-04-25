package com.oschrenk.expando

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Cookie, `Set-Cookie`}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer, ThrottleMode}

import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.io.{Source => FileSource}

case class ExpandedUri(source: Uri, result: Either[String, Uri])

object Expando {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    def request(url: Uri, cookie: Option[Cookie] = None): Future[HttpResponse] = {
      Http()
        .singleRequest(HttpRequest(
          method = HttpMethods.GET,
          uri = url,
          headers = cookie.toList))
    }

    def redirectOrResult(originalUrl: Uri, newLocation: Option[Uri], response: HttpResponse)(implicit materializer: Materializer): Future[Uri] = {
      response.status match {
        case StatusCodes.Found | StatusCodes.MovedPermanently | StatusCodes.SeeOther | StatusCodes.TemporaryRedirect ⇒
          response.discardEntityBytes()

          // deal with cookie protection schemes
          val cookies = response.header[`Set-Cookie`].map(_.cookie).map(c => Cookie(c.name, c.value))

          // deal with malformed location values containing whitespaces
          val location = response.headers.find(h => h.lowercaseName().equals("location"))
            .map(h => Uri.apply(h.value().replace(" ", "%20")))

          // deal with relative urls in location value
          val newUri = location match {
            case Some(uri) if uri.isRelative =>
              newLocation.getOrElse(originalUrl).withPath(uri.path)
            case Some(uri)  =>
              uri
            case None =>
              throw new IllegalArgumentException("empty location header on redirect")
          }

          request(newUri, cookies).flatMap(redirectOrResult(originalUrl, Some(newUri), _))
        case _ ⇒
          response.discardEntityBytes()
          Future.successful(newLocation.getOrElse(originalUrl))
      }
    }

    def followRedirectOrResult(uri: Uri): Future[ExpandedUri] = {
      if (uri.path.isEmpty) {
        Future.successful(ExpandedUri(uri, Right(uri)))
      } else {
        request(uri).flatMap{res =>
          redirectOrResult(uri, None, res)
            .map(newUri => ExpandedUri(uri, Right(newUri)))
            .recover{
              case e => ExpandedUri(uri, Left(e.getMessage))
            }
        }
      }
    }

    val toUri: Flow[String, Uri, _] = Flow[String]
      .map(Uri.apply)
    val expandUri: Flow[Uri, ExpandedUri, _] = Flow[Uri]
      .mapAsyncUnordered(Config.Parallelism)(followRedirectOrResult)
    val printExpandedUri: Sink[ExpandedUri, Future[Done]] = Sink.foreach{ expandedUri =>
      println(expandedUri)
    }
    val urlSource =
      Source
        .fromIterator(() => FileSource.fromFile(Config.Source.Path.get, Config.Source.Encoding).getLines())
        .throttle(Config.Throttle.Elements, Config.Throttle.Rate, Config.Throttle.Burst, ThrottleMode.shaping)

    urlSource
      .via(toUri)
      .via(expandUri)
      .runWith(printExpandedUri)
      .onComplete { _ =>
        println("http://i.am.done.com http://i.am.done.com")
      }
  }

}
