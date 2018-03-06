import cats._
import cats.implicits._
import doobie._
import doobie.implicits._
import fs2.Stream

trait Database[F[_]] {
  def query[R: Composite](q: Fragment): F[List[R]] // TODO remove Composite http://tpolecat.github.io/doobie/docs/12-Custom-Mappings.html
  def queryStream[R: Composite](q: Fragment): F[R]
  def insert(q: Fragment): F[Boolean]
  def insertAndGet[R: Composite](q: Fragment, cols: String*): F[Option[R]]
  def remove(q: Fragment): F[Unit]
  def exists(q: Fragment): F[Boolean]
}

object Database extends DatabaseInstances {
  def apply[F[_]](implicit D: Database[F]): Database[F] = D
}

sealed abstract class DatabaseInstances {
  private[this] val logger = org.log4s.getLogger

  implicit def doobieDatabase[F[_]: Monad](xa: Transactor[F]): Database[Stream[F, ?]] =
    new Database[Stream[F, ?]] {
      def query[R: Composite](q: Fragment): Stream[F, List[R]] =
        Stream.eval(q.query[R].to[List].transact(xa))

      def queryStream[R: Composite](q: Fragment): Stream[F, R] =
        q.query[R].stream.transact(xa)

      def insert(q: Fragment): Stream[F, Boolean] = {
        println(s"INSERT : $q")
        Stream.eval(q.update.run.transact(xa).map(_ > 0))
      }

      def insertAndGet[R: Composite](q: Fragment, cols: String*): Stream[F, Option[R]] = {
        println(s"YOLO : ${q}")

        val sql = q.update

        println(s"HEY THERE : ${sql.sql}")

        Stream
          .eval(
            sql
              .withUniqueGeneratedKeys[R](cols: _*)
              .attemptSomeSqlState {
                case doobie.postgres.sqlstate.class23.UNIQUE_VIOLATION =>
                  "Coucou"
              }
              .transact(xa)
          )
          .map(_.toOption)
      }

      def remove(q: Fragment): Stream[F, Unit] = ???

      def exists(q: Fragment): Stream[F, Boolean] = {
        Stream.eval(q.query[Int].unique.map(_ > 0).transact(xa))
      }
    }
}
