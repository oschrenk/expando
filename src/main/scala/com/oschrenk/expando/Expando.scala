package com.oschrenk.expando

import java.time.LocalDateTime

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.{Cookie, `Set-Cookie`}
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Materializer}

import scala.concurrent.{ExecutionContextExecutor, Future, TimeoutException}
import scala.io.{Source => FileSource}
import scala.util.{Failure, Success, Try}


sealed trait ExpandedUri
case class NoRedirect(source: Uri) extends ExpandedUri
case class WithRedirect(source: Uri, target: Uri) extends ExpandedUri
case class Failed(source: Uri, error: String) extends ExpandedUri

object Expando {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer(
      ActorMaterializerSettings(system)
        //.withDispatcher("my-custom-dispatcher")
    )
    implicit val executionContext: ExecutionContextExecutor =
    system.dispatchers.lookup("execution-contexts.custom")

    def flow() = {
    }

    def buildRequest(uri: Uri, cookie: Option[Cookie]) = {
      HttpRequest(
          method = HttpMethods.GET,
          uri = uri,
          headers = cookie.toList)
    }

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
      response.discardEntityBytes()

      response.status match {
        case StatusCodes.Found | StatusCodes.MovedPermanently | StatusCodes.SeeOther | StatusCodes.TemporaryRedirect ⇒

          // deal with cookie protection schemes
          val cookies = response.header[`Set-Cookie`].map(_.cookie).map(c => Cookie(c.name, c.value))

          // deal with malformed location values containing whitespaces
          val location = response.headers.find(h => h.lowercaseName().equals("location"))
            .map { header =>
              try {
                Uri.apply(header.value().replace(" ", "%20"))
              }
              catch {
                case e: IllegalUriException => throw new IllegalArgumentException(s"Malformed Location header |${header.value()}| for $originalUrl: ${e.getMessage}")
              }
             }

          // deal with relative urls in location value
          val newUri = location match {
            case Some(uri) if uri.isRelative =>
              uri.resolvedAgainst(newLocation.getOrElse(originalUrl))
            case Some(uri)  =>
              uri
            case None =>
              throw new IllegalArgumentException(s"empty location header on redirect for $originalUrl")
          }
          Future.successful(Right(newUri))
        case _ ⇒
          Future.successful(Right(newLocation.getOrElse(originalUrl)))
      }
    }

    def timeout[T](f: Future[T]): Future[T] = {
      import akka.pattern.after
      val a = after(duration = Config.Timeout, using = system.scheduler)(Future.failed(new TimeoutException("Future timed out!")))
      Future.firstCompletedOf(Seq(f, a))
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
                .recover{ case e => Left(e.getMessage)}
                .map {
                  case Left(error) => Failed(uri, error)
                  case Right(newUri) => if (uri == newUri) NoRedirect(uri) else WithRedirect(uri, newUri)
                }
          }
      }
    }

    val toUri: Flow[String, Uri, _] = Flow[String].map(Uri.apply)
    val expandUri: Flow[Uri, ExpandedUri, _] = Flow[Uri]
      .mapAsyncUnordered(Config.Parallelism)(followRedirectOrResult)
    val printExpandedUri: Sink[ExpandedUri, Future[Done]] = Sink.foreach {
      case NoRedirect(uri) =>
        println(s"$uri 2xx $uri ${LocalDateTime.now}")
      case WithRedirect(from, to) =>
        println(s"$from 3xx $to ${LocalDateTime.now}")
      case Failed(uri, error) =>
        println(s"$uri 5xx $error ${LocalDateTime.now}")
    }
    val urlSource =
      Source
        .fromIterator(() => FileSource.fromFile(Config.Source.Path.get, Config.Source.Encoding).getLines())
    val httpFlow: Flow[(HttpRequest, Uri), (Try[HttpResponse], Uri), NotUsed] = {
      import scala.concurrent.duration._
      Http().superPool().completionTimeout(5.seconds)
    }

    val toRequest = Flow[Uri].map(u => buildRequest(u, None))

    urlSource
      .via(toUri)
      .via(toRequest).map(r => (r, r.uri))
      .via(httpFlow)
      .via(u =>

      .runWith(printExpandedUri)
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
