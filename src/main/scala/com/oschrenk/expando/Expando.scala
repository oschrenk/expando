package com.oschrenk.expando

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Cookie, `Set-Cookie`}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, Materializer}

import scala.concurrent.{ExecutionContextExecutor, Future, TimeoutException}
import scala.io.{Source => FileSource}
import scala.util.{Failure, Success}

object Expando {
  case class Resource(uri: Uri, cookies: Option[Cookie]) {
    override def toString() = s"$uri, cookies: $cookies"
  }

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    def redirectOrResult
        (original: Resource, response: HttpResponse)
        (implicit materializer: Materializer): Option[(Resource, Unit)] = {
      response.status match {
        case StatusCodes.Found | StatusCodes.MovedPermanently | StatusCodes.SeeOther | StatusCodes.TemporaryRedirect ⇒
          println(s"Got redirected with ${response.status} on $original")

          // deal with cookie protection schemes
          val cookies = response.header[`Set-Cookie`].map(_.cookie).map(c => Cookie(c.name, c.value))

          // deal with malformed location values containing whitespaces
          response
            .headers
            .find(_.lowercaseName().equals("location"))
            .map { header =>
              try {
                Uri.apply(header.value().replace(" ", "%20"))
              }
              catch {
                case e: IllegalUriException =>
                  throw new IllegalArgumentException(s"Malformed Location header |${header.value()}| for $original: ${e.getMessage}")
              }
            }
            .map {
              case uri if uri.isRelative =>
                (Resource(uri.resolvedAgainst(original.uri), cookies), ())
              case uri  =>
                (Resource(uri, cookies), ())
            }
        case other ⇒
          println(s"Got $other on $original")

          // marks the end of the expansion for this URI,
          // so that the Source can proceed with the next
          None
      }
    }

    val urlSource =
      Source
        .fromIterator(() => FileSource.fromFile(
          Config.Source.Path.get,
          Config.Source.Encoding).getLines())

    def resources(originalUri: Resource): Source[Unit, _] = {
      def resolve(what: Resource): Future[Option[(Resource, Unit)]] =
        Http()
          .singleRequest(HttpRequest(
            method = HttpMethods.GET,
            uri = what.uri,
            headers = what.cookies.toList))
          .map { res =>
            res.discardEntityBytes(materializer)
            redirectOrResult(what, res)
          }
          .recover {
            case ex =>
              println(s"Got FAILURE on $what. Exception: ${ex.getMessage}")
              None
          }

      Source
        .unfoldAsync[Resource, Unit](originalUri) { uri =>
          println(s"Expanding $uri")
          resolve(uri)
        }
    }

    urlSource
      .via(Flow[String].map(uri => Resource(Uri(uri), None)))
      .flatMapConcat(resources)
      .runWith(Sink.ignore)
      .onComplete {
        case Success(_) =>
          println("http://i.am.done.com 200 http://i.am.done.com")
          system.terminate()
        case Failure(error) =>
          println(s"http://i.am.broken.com 666 $error")
          system.terminate()
      }
  }

}
