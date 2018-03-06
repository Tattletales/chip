import User.{Name, Password}
import UsersActions._
import cats.Monad
import cats.data.OptionT
import fs2._
import doobie._
import doobie.implicits._

trait Users[F[_]] {
  def addUser(name: Name, password: Password): F[Option[User]]
  def removeUser(user: User): F[Unit]
  def searchUser(name: Name): F[List[User]]
  def searchUserStream(name: Name): F[User]
}

object Users extends UsersInstances {
  def apply[F[_]](implicit U: Users[F]): Users[F] = U
}

sealed abstract class UsersInstances {
  implicit def replicated[F[_]: Monad](
      db: Database[Stream[F, ?]],
      distributor: Distributor[Stream[F, ?], F, UsersAction]
  ): Users[Stream[F, ?]] = new Users[Stream[F, ?]] {
    def addUser(name: Name, password: Password): Stream[F, Option[User]] =
      for {
        _ <- db.insert(sql"""
           INSERT INTO users (name, password)
           VALUES (${name.name}, ${password.p})
       """)

        _ <- distributor.share(AddUser(name, password))
      } yield None

    def removeUser(user: User): Stream[F, Unit] =
      for {
        _ <- db.remove(sql"""
           DELETE FROM users
           WHERE name = ${user.name}
         """)

        _ <- distributor.share(RemoveUser(user))
      } yield ()

    def searchUser(name: Name): Stream[F, List[User]] =
      db.query[User](sql"""
         SELECT *
         FROM users
         WHERE name = $name
       """)

    def searchUserStream(name: Name): Stream[F, User] =
      db.queryStream[User](sql"""
         SELECT *
         FROM users
         WHERE name = ${name.name}
       """)
  }
}

object UsersActions {
  sealed trait UsersAction
  case class AddUser(name: Name, password: Password) extends UsersAction
  case class RemoveUser(user: User) extends UsersAction

  implicit val namedUsersAction: Named[UsersAction] = new Named[UsersAction] {
    val name: String = "Users"
  }

  implicit val replicableUsersAction: Replicable[UsersAction] =
    new Replicable[UsersAction] {
      def replicate[F[_]](r: Repo[F]): Sink[F, UsersAction] = _.flatMap {
        case AddUser(name, password) =>
          r.users.addUser(name, password).map(_ => ())
        case RemoveUser(user) => r.users.removeUser(user).map(_ => ())
      }
    }
}
