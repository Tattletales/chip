package backend.programs

import cats.effect.Effect
import fs2._
import utils.stream.Utils.interruptAfter

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Program DSL
  */
trait Program[F[_], A] {

  /**
    * Convert a program to a [[Stream]]
    */
  def run: Stream[F, A]

  /**
    * Run the program for the given `duration`
    */
  def runFor(duration: FiniteDuration)(implicit F: Effect[F], ec: ExecutionContext): Stream[F, A] =
    run.through(interruptAfter(duration))
}
