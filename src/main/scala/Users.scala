import Database.Query
import UsersActions._
import cats.Monad
import cats.data.OptionT
import doobie.util.composite.Composite
import fs2._

trait Users[F[_]] {
  def addUser(name: String, password: String): F[Option[User]]
  def removeUser(user: User): F[Unit]
  def searchUser(name: String): F[List[User]]
}

object Users extends UsersInstances {
  def apply[F[_]](implicit U: Users[F]): Users[F] = U
}

sealed abstract class UsersInstances {
  implicit def replicated[F[_]: Monad](
    db: Database[Stream[F, ?]],
    distributor: Distributor[Stream[F, ?], UsersAction]
  ): Users[Stream[F, ?]] = new Users[Stream[F, ?]] {
    def addUser(name: String, password: String): Stream[F, Option[User]] =
      (for {
        user <- OptionT(db.insertAndGet[User](Query(s"""
           |INSERT INTO users (name, password)
           |VALUES ($name, $password)
       """.stripMargin), Seq("id", "user", "password"): _*))

        _ <- OptionT.liftF(distributor.share(AddUser(name, password)))
      } yield user).value

    def removeUser(user: User): Stream[F, Unit] =
      for {
        _ <- db.remove(Query(s"""
           |DELETE FROM users
           |WHERE ???
         """.stripMargin))

        _ <- distributor.share(RemoveUser(user))
      } yield ()

    def searchUser(name: String): Stream[F, List[User]] = db.query[User](Query(s"""
         |SELECT *
         |FROM users
         |WHERE name = $name
       """.stripMargin))
  }
}

object UsersActions {
  sealed trait UsersAction
  case class AddUser(name: String, password: String) extends UsersAction
  case class RemoveUser(user: User) extends UsersAction
}
