package utils.stream

import java.io._

import cats.effect.{Async, Effect, Sync}
import fs2._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object Utils {
  def log[F[_], A](prefix: String)(implicit F: Sync[F]): Pipe[F, A, A] = _.evalMap { a =>
    F.delay { println(s"$prefix> $a"); a }
  }

  def logToFile[F[_], A](postfix: String, path: String)(implicit F: Sync[F]): Pipe[F, A, A] = {
    val file = new File(path)
    val bw = new BufferedWriter(new FileWriter(file, true))

    _.evalMap { a =>
      F.delay { bw.write(s"${System.currentTimeMillis()} $a $postfix\n"); bw.flush(); a }
    }
  }

  def interruptAfter[F[_]: Effect, A](
      delay: FiniteDuration)(implicit F: Async[F], ec: ExecutionContext): Pipe[F, A, A] = { s =>
    for {
      scheduler <- Scheduler[F](corePoolSize = 2)
      cancellationSignal <- Stream.eval(async.signalOf(false))
      interruptedS <- s
        .interruptWhen(cancellationSignal)
        .merge(scheduler.sleep_(delay) ++ Stream.eval_(cancellationSignal.set(true)))
    } yield interruptedS
  }
}
