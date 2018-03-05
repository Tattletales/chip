import Database.Query
import cats._
import cats.implicits._
import doobie._
import doobie.implicits._
import fs2.Stream

trait Database[F[_]] {
  def query[R: Composite](q: Query): F[List[R]] // TODO remove Composite http://tpolecat.github.io/doobie/docs/12-Custom-Mappings.html
  def queryStream[R: Composite](q: Query): F[R]
  def insert(q: Query): F[Boolean]
  def insertAndGet[R: Composite](q: Query, cols: String*): F[Option[R]]
  def remove(q: Query): F[Unit]
  def exists(q: Query): F[Boolean]
}

object Database extends DatabaseInstances {
  def apply[F[_]](implicit D: Database[F]): Database[F] = D

  case class Query(q: String) extends AnyVal
}

sealed abstract class DatabaseInstances {
  implicit def doobieDatabase[F[_]: Monad](xa: Transactor[F]): Database[Stream[F, ?]] =
    new Database[Stream[F, ?]] {
      def query[R: Composite](q: Query): Stream[F, List[R]] =
        Stream.eval(sql"$q".query[R].to[List].transact(xa))

      def queryStream[R: Composite](q: Query): Stream[F, R] =
        sql"$q".query[R].stream.transact(xa)

      def insert(q: Query): Stream[F, Boolean] =
        Stream.eval(sql"$q".update.run.transact(xa).map(_ > 0))

      def insertAndGet[R: Composite](q: Query, cols: String*): Stream[F, Option[R]] =
        Stream
          .eval(
            sql"$q".update
              .withUniqueGeneratedKeys[R](cols: _*)
              .attemptSomeSqlState {
                case doobie.postgres.sqlstate.class23.UNIQUE_VIOLATION =>
                  "Coucou"
              }
              .transact(xa)
          )
          .map(_.toOption)

      def remove(q: Query): Stream[F, Unit] = ???

      def exists(q: Query): Stream[F, Boolean] =
        Stream.eval(sql"$q".query[Int].unique.map(_ > 0).transact(xa))
    }
}
