package com.oschrenk.expando

import java.util.concurrent.TimeUnit

import com.typesafe.config.ConfigFactory

import scala.concurrent.duration.{Duration, FiniteDuration}

object Config {

  private val config = ConfigFactory.load()

  object Source {
    val Path: Option[String] = if (config.hasPath("expando.source.path")) {
      Some(config.getString("expando.source.path"))
    } else {
      None
    }
    val Encoding: String = config.getString("expando.source.encoding")
  }

  object Throttle {
    implicit def asFiniteDuration(d: java.time.Duration): FiniteDuration =
      scala.concurrent.duration.FiniteDuration(d.getSeconds, TimeUnit.SECONDS)
    val Elements: Int = config.getInt("expando.throttle.elements")
    val Rate: FiniteDuration= config.getDuration("expando.throttle.rate")
    val Burst: Int = config.getInt("expando.throttle.burst")
  }

  val Parallelism: Int = config.getInt("expando.parallelism")

}
