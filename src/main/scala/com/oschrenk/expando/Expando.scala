package com.oschrenk.expando

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{ HttpRequest, HttpResponse }
import akka.stream.ActorMaterializer

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.util.{ Failure, Success }

object Expando {
  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val executionContext: ExecutionContextExecutor = system.dispatcher

    val responseFuture: Future[HttpResponse] = Http().singleRequest(HttpRequest(uri = "http://akka.io"))

    responseFuture.onComplete { f =>
      f match {
        case Success(res) => println(res)
        case Failure(_) => sys.error("something wrong")
      }
      system.terminate()
    }
  }
}
