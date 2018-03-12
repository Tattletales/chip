import cats.effect.Sync
import fs2.Pipe

object Utils {
  def log[F[_], A](prefix: String)(implicit F: Sync[F]): Pipe[F, A, A] = _.evalMap { a =>
    F.delay { println(s"$prefix> $a"); a }
  }
}
