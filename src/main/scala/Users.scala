import Database.Query
import User.{Name, Password}
import UsersActions._
import cats.Monad
import cats.data.OptionT
import doobie.util.composite.Composite
import fs2._

trait Users[F[_]] {
  def addUser(name: Name, password: Password): F[Option[User]]
  def removeUser(user: User): F[Unit]
  def searchUser(name: Name): F[List[User]]
}

object Users extends UsersInstances {
  def apply[F[_]](implicit U: Users[F]): Users[F] = U
}

sealed abstract class UsersInstances {
  implicit def replicated[F[_]: Monad](
    db: Database[Stream[F, ?]],
    distributor: Distributor[Stream[F, ?], UsersAction]
  ): Users[Stream[F, ?]] = new Users[Stream[F, ?]] {
    def addUser(name: Name, password: Password): Stream[F, Option[User]] =
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

    def searchUser(name: Name): Stream[F, List[User]] = db.query[User](Query(s"""
         |SELECT *
         |FROM users
         |WHERE name = $name
       """.stripMargin))
  }
}

object UsersActions {
  sealed trait UsersAction
  case class AddUser(name: Name, password: Password) extends UsersAction
  case class RemoveUser(user: User) extends UsersAction

  implicit val namedUsersAction: Named[UsersAction] = new Named[UsersAction] {
    val name: String = "Users"
  }

  implicit val replicableUsersAction: Replicable[UsersAction] = new Replicable[UsersAction] {
    def replicate[F[_]](r: Repo[F]): Sink[F, UsersAction] = _.flatMap {
      case AddUser(name, password) => r.users.addUser(name, password).map(_ => ())
      case RemoveUser(user)        => r.users.removeUser(user).map(_ => ())
    }
  }
}
