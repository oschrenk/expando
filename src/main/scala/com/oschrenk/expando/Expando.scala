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
import scala.util.{Failure, Success}


sealed trait ExpandedUri
case class NoRedirect(source: Uri) extends ExpandedUri
case class WithRedirect(source: Uri, target: Uri) extends ExpandedUri
case class Failed(source: Uri, error: String) extends ExpandedUri

object Expando {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    def request(url: Uri, cookie: Option[Cookie] = None): Future[Either[String, HttpResponse]] = {
      Http()
        .singleRequest(HttpRequest(
          method = HttpMethods.GET,
          uri = url,
          headers = cookie.toList))
        .map(Right.apply)
        .recover{ case e => Left(e.getMessage)}
    }

    def redirectOrResult(originalUrl: Uri, newLocation: Option[Uri], response: HttpResponse)(implicit materializer: Materializer): Future[Either[String, Uri]] = {
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

          request(newUri, cookies).flatMap {
            case Left(error) => Future.successful(Left(error))
            case Right(res) => redirectOrResult(originalUrl, Some(newUri), res)
          }
        case _ ⇒
          response.discardEntityBytes()
          Future.successful(Right(newLocation.getOrElse(originalUrl)))
      }
    }

    def followRedirectOrResult(uri: Uri): Future[ExpandedUri] = {
      if (uri.path.isEmpty) {
        Future.successful(NoRedirect(uri))
      } else {
        request(uri)
          .flatMap {
            case Left(error) => Future.successful(Failed(uri, error))
            case Right(res) =>
              redirectOrResult(uri, None, res)
                .map {
                  case Left(error) => Failed(uri, error)
                  case Right(newUri) => if (uri == newUri) NoRedirect(uri) else WithRedirect(uri, newUri)
                }
          }
      }
    }

    val toUri: Flow[String, Uri, _] = Flow[String]
      .map(Uri.apply)
    val expandUri: Flow[Uri, ExpandedUri, _] = Flow[Uri]
      .mapAsyncUnordered(Config.Parallelism)(followRedirectOrResult)
    val printExpandedUri: Sink[ExpandedUri, Future[Done]] = Sink.foreach {
      case NoRedirect(uri) =>
        println(s"$uri 2xx $uri")
      case WithRedirect(from, to) =>
        println(s"$from 3xx $to")
      case Failed(uri, error) =>
        println(s"$uri 5xx $error")
    }
    val urlSource =
      Source
        .fromIterator(() => FileSource.fromFile(Config.Source.Path.get, Config.Source.Encoding).getLines())
        .throttle(Config.Throttle.Elements, Config.Throttle.Rate, Config.Throttle.Burst, ThrottleMode.shaping)

    urlSource
      .via(toUri)
      .via(expandUri)
      .runWith(printExpandedUri)
      .onComplete {
        case Success(_) => println("http://i.am.done.com 200 http://i.am.done.com")
        case Failure(error) => println(s"http://i.am.broken.com 666 $error")
      }
  }

}
