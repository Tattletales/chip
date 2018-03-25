package backend.storage

import cats._
import cats.implicits._
import doobie._
import doobie.implicits._
import shapeless.tag.@@
import backend.storage.Database.Column

trait Database[F[_]] {
  def query[R: Composite](q: Fragment): F[List[R]] // TODO remove Composite http://tpolecat.github.io/doobie/docs/12-Custom-Mappings.html
  def insert(q: Fragment): F[Unit]
  def insertAndGet[R: Composite](q: Fragment, cols: Column*): F[Option[R]]
  def remove(q: Fragment): F[Unit]
  def exists(q: Fragment): F[Boolean]
}

object Database extends DatabaseInstances {
  sealed trait ColumnTag
  type Column = String @@ ColumnTag

  def apply[F[_]](implicit D: Database[F]): Database[F] = D
}

sealed abstract class DatabaseInstances {
  private[this] val logger = org.log4s.getLogger

  implicit def doobieDatabase[F[_]: Monad](xa: Transactor[F]): Database[F] =
    new Database[F] {
      def query[R: Composite](q: Fragment): F[List[R]] =
        q.query[R].to[List].transact(xa)

      def insert(q: Fragment): F[Unit] = {
        println(s"INSERT : $q")
        q.update.run.transact(xa).map(_ => ())
      }

      def insertAndGet[R: Composite](q: Fragment, cols: Column*): F[Option[R]] =
        q.update
          .withUniqueGeneratedKeys[R](cols: _*)
          .attemptSomeSqlState {
            case doobie.postgres.sqlstate.class23.UNIQUE_VIOLATION =>
              "Coucou"
          }
          .transact(xa)
          .map(a => a.toOption)

      def remove(q: Fragment): F[Unit] = ???

      def exists(q: Fragment): F[Boolean] =
        q.query[Int].unique.map(_ > 0).transact(xa)
    }
}
