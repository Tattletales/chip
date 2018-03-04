import UsersActions._
import cats.Monad
import cats.data.OptionT
import doobie.util.composite.Composite
import fs2._

trait Users[F[_], User] {
  def addUser(name: String, password: String): F[Option[User]]
  def removeUser(user: User): F[Unit]
  def searchUser(name: String): F[List[User]]
}

object Users extends UsersInstances {
  def apply[F[_], User](implicit U: Users[F, User]): Users[F, User] = U
}

sealed abstract class UsersInstances {
  implicit def replicated[F[_]: Monad, User: Composite](
    db: Database[Stream[F, ?], String],
    distributor: Distributor[Stream[F, ?], UsersAction]
  ): Users[Stream[F, ?], User] =
    new Users[Stream[F, ?], User] {
      def addUser(name: String, password: String): Stream[F, Option[User]] =
        (for {
          user <- OptionT(db.insertAndGet[User](s"""
           |INSERT INTO users (name, password)
           |VALUES ($name, $password)
       """.stripMargin, Seq("id", "user", "password"): _*))

          _ <- OptionT.liftF(distributor.share(AddUser(name, password)))
        } yield user).value

      def removeUser(user: User): Stream[F, Unit] =
        for {
          _ <- db.remove(s"""
           |DELETE FROM users
           |WHERE ???
         """.stripMargin)

          _ <- distributor.share(RemoveUser(user))
        } yield ()

      def searchUser(name: String): Stream[F, List[User]] = db.query[User](s"""
         |SELECT *
         |FROM users
         |WHERE name = $name
       """.stripMargin)
    }
}

object UsersActions {
  sealed trait UsersAction
  case class AddUser(name: String, password: String) extends UsersAction
  case class RemoveUser[User](user: User) extends UsersAction
}
