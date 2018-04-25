package utils

import java.io._

import cats.effect.Sync
import fs2._

object StreamUtils {
  def log[F[_], A](prefix: String)(implicit F: Sync[F]): Pipe[F, A, A] = _.evalMap { a =>
    F.delay { println(s"$prefix> $a"); a }
  }

  def logToFile[F[_], A](postfix: String, path: String)(implicit F: Sync[F]): Pipe[F, A, A] = {
    val file = new File(path)
    val bw = new BufferedWriter(new FileWriter(file, true))

    _.evalMap { a =>
      F.delay { bw.write(s"${System.currentTimeMillis()} $a $postfix"); a }
    }
  }
}
