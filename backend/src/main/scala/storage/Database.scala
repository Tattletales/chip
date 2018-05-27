package backend.storage

import backend.storage.Database.Column
import cats._
import cats.implicits._
import doobie._
import doobie.implicits._
import shapeless.tag.@@

/**
  * Database DSL
  */
trait Database[F[_]] {

  /**
    * Query the database and return the results `R`.
    */
  def query[R: Composite](q: Fragment): F[List[R]]

  /**
    * Insert the described rows.
    */
  def insert(q: Fragment, qs: Fragment*): F[Unit]

  /**
    * Insert the described row and yield a unique
    * generated key based on the provided columns.
    */
  def insertAndGet[R: Composite](q: Fragment, cols: Column*): F[R]

  /**
    * Returns true if the given querie's result is non-empty.
    */
  def exists(q: Fragment): F[Boolean]
}

object Database {

  /* ------ Interpreters ------ */

  /**
    * Interpreter to `Doobie`
    */
  def doobie[F[_]: MonadError[?[_], Throwable]](xa: Transactor[F]): Database[F] =
    new Database[F] {
      def query[R: Composite](q: Fragment): F[List[R]] =
        q.query[R].to[List].transact(xa)

      def insert(q: Fragment, qs: Fragment*): F[Unit] =
        qs.foldLeft(q.update.run) {
            _ *> _.update.run
          }
          .transact(xa)
          .map(_ => ()) // Ignore output

      def insertAndGet[R: Composite](q: Fragment, cols: Column*): F[R] =
        q.update
          .withUniqueGeneratedKeys[R](cols: _*)
          .transact(xa)

      def exists(q: Fragment): F[Boolean] =
        q.query[Int].unique.map(_ > 0).transact(xa)
    }

  /* ------ Types ------ */
  sealed trait ColumnTag
  type Column = String @@ ColumnTag
}
