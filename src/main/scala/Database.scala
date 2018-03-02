import doobie._
import doobie.implicits._
import cats._
import cats.data._
import cats.effect.IO
import cats.implicits._

trait Database[F[_], Query] {
  def query[R: Composite](q: Query): F[List[R]] // TODO remove Composite http://tpolecat.github.io/doobie/docs/12-Custom-Mappings.html
  def insert(q: Query): F[Boolean]
  def insertAndGet[R](q: Query, cols: String*): F[Option[R]]
  def remove(q: Query): F[Unit]
  def exists(q: Query): F[Boolean]
}

object Database extends DatabaseInstances {
  def apply[F[_], Query](implicit D: Database[F, Query]): Database[F, Query] = D
}

sealed abstract class DatabaseInstances {
  implicit def doobieDatabase: Database[ConnectionIO, String] =
    new Database[ConnectionIO, String] {

      def query[R: Composite](q: String): ConnectionIO[List[R]] =
        sql"$q".query[R].to[List]

      def insert(q: String): ConnectionIO[Boolean] =
        sql"$q".update.run.map(_ > 0)

      def insertAndGet[R](q: String, cols: String*): ConnectionIO[Option[R]] =
        sql"$q".update.withUniqueGeneratedKeys[R](cols:_*).attemptSomeSqlState {
          case doobie.postgres.sqlstate.class23.UNIQUE_VIOLATION => "Coucou"
        }.map(_.toOption)

      def exists(q: String): ConnectionIO[Boolean] = ???

      def remove(q: String): ConnectionIO[Unit] = ???
    }
}
