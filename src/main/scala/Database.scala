import doobie._
import doobie.implicits._
import cats._
import cats.data._
import cats.effect.IO
import cats.implicits._

trait Database[F[_], Query] {
  def query[R: Composite](q: Query): F[List[R]] // TODO remove Composite http://tpolecat.github.io/doobie/docs/12-Custom-Mappings.html
}

object Database extends DatabaseInstances {
  def apply[F[_], Query](implicit D: Database[F, Query]): Database[F, Query] = D
}

sealed abstract class DatabaseInstances {
  implicit def doobieDatabase(xa: Transactor[IO]): Database[IO, String] =
    new Database[IO, String] {
      def query[R: Composite](q: String): IO[List[R]] =
        sql"$q".query[R].to[List].transact(xa)
    }
}
