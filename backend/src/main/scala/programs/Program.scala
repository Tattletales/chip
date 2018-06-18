package backend
package programs

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
}

object Program {

  /**
    * Run the program for the given `duration`
    */
  def runFor[F[_]: Effect, A](program: Program[F, A])(duration: FiniteDuration)(
      implicit ec: ExecutionContext): Stream[F, A] =
    program.run.through(interruptAfter(duration))
}
