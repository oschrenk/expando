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

  implicit def asFiniteDuration(d: java.time.Duration): FiniteDuration =
    scala.concurrent.duration.FiniteDuration(d.getSeconds, TimeUnit.SECONDS)
  val Timeout: FiniteDuration = config.getDuration("expando.timeout")
  val Parallelism: Int = config.getInt("expando.parallelism")

}
