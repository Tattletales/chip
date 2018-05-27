package vault.programs

import fs2._
import cats.effect.Effect
import utils.StreamUtils.interruptAfter

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Program DSL
  */
trait Program[F[_]] {

  /**
    * Convert a program to a [[Stream]]
    */
  def run: Stream[F, Unit]

  /**
    * Run the program for the given `duration`
    */
  def runFor(duration: FiniteDuration)(implicit F: Effect[F],
                                       ec: ExecutionContext): Stream[F, Unit] =
    run.through(interruptAfter(duration))
}