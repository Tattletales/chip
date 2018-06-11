package utils.stream

import java.io._

import cats.effect.{Async, Effect, Sync}
import fs2._
import fs2.Stream
import threadPools.ThreadPools

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

object Utils {
  def log[F[_], A](prefix: String)(implicit F: Sync[F]): Pipe[F, A, A] = _.evalMap { a =>
    F.delay { println(s"$prefix> $a"); a }
  }

  def logToFile[F[_], A](prefix: String, path: String)(implicit F: Effect[F]): Pipe[F, A, A] = {
    implicit val e: ExecutionContext = ThreadPools.ioThreadPool

    _.flatMap(a => {
      val writeToFile = io.writeOutputStreamAsync[F](
        F.delay(new BufferedOutputStream(new FileOutputStream(path, true))))
      writeToFile(Stream(s"${System.currentTimeMillis()} $prefix $a\n".getBytes.toSeq: _*)) >> Stream(
        a)
    })
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
