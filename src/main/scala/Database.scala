import doobie._
import doobie.implicits._
import cats._
import cats.data._
import cats.effect.IO
import cats.implicits._
import fs2.Stream

trait Database[F[_], Query] {
  def query[R: Composite](q: Query): F[List[R]] // TODO remove Composite http://tpolecat.github.io/doobie/docs/12-Custom-Mappings.html
  def insert(q: Query): F[Boolean]
  def insertAndGet[R: Composite](q: Query, cols: String*): F[Option[R]]
  def remove(q: Query): F[Unit]
  def exists(q: Query): F[Boolean]
}

object Database extends DatabaseInstances {
  def apply[F[_], Query](implicit D: Database[F, Query]): Database[F, Query] = D
}

sealed abstract class DatabaseInstances {
  implicit def doobieDatabase[F[_]: Monad](xa: Transactor[F]): Database[Stream[F, ?], String] =
    new Database[Stream[F, ?], String] {
      def query[R: Composite](q: String): Stream[F, List[R]] =
        Stream.eval(sql"$q".query[R].to[List].transact(xa))

      def insert(q: String): Stream[F, Boolean] =
        Stream.eval(sql"$q".update.run.transact(xa).map(_ > 0))

      def insertAndGet[R: Composite](q: String, cols: String*): Stream[F, Option[R]] =
        Stream
          .eval(
            sql"$q".update
              .withUniqueGeneratedKeys[R](cols: _*)
              .attemptSomeSqlState {
                case doobie.postgres.sqlstate.class23.UNIQUE_VIOLATION => "Coucou"
              }
              .transact(xa)
          )
          .map(_.toOption)

      def remove(q: String): Stream[F, Unit] = ???

      def exists(q: String): Stream[F, Boolean] =
        Stream.eval(sql"$q".query[Int].unique.map(_ > 0).transact(xa))
    }
}
