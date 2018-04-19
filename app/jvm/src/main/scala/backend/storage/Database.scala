package backend.storage

import cats._
import cats.implicits._
import doobie._
import doobie.implicits._
import shapeless.tag.@@
import backend.storage.Database.Column

trait Database[F[_]] {
  def query[R: Composite](q: Fragment): F[List[R]]
  def insert(q: Fragment, qs: Fragment*): F[Unit]
  def insertAndGet[R: Composite](q: Fragment, cols: Column*): F[R]
  def exists(q: Fragment): F[Boolean]
}

object Database extends DatabaseInstances {
  sealed trait ColumnTag
  type Column = String @@ ColumnTag

  def apply[F[_]](implicit D: Database[F]): Database[F] = D
}

sealed abstract class DatabaseInstances {
  implicit def doobieDatabase[F[_]: MonadError[?[_], Throwable]](xa: Transactor[F]): Database[F] =
    new Database[F] {
      def query[R: Composite](q: Fragment): F[List[R]] =
        q.query[R].to[List].transact(xa)

      def insert(q: Fragment, qs: Fragment*): F[Unit] =
        qs.foldLeft(q.update.run) {
            _ *> _.update.run
          }
          .transact(xa)
          .map(_ => ())

      def insertAndGet[R: Composite](q: Fragment, cols: Column*): F[R] =
        q.update
          .withUniqueGeneratedKeys[R](cols: _*)
          .transact(xa)

      def exists(q: Fragment): F[Boolean] =
        q.query[Int].unique.map(_ > 0).transact(xa)
    }
}
