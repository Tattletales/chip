import UsersActions._
import cats.Monad
import cats.effect.Effect
import cats.implicits._
import doobie.implicits._
import io.circe.generic.auto._
import org.http4s.EntityDecoder

trait Users[F[_]] {
  def addUser(name: String): F[User]
  //def removeUser(user: String): F[Unit]
  def searchUser(name: String): F[List[User]]
  def getUser(id: String): F[Option[User]]
}

object Users extends UsersInstances {
  def apply[F[_]](implicit U: Users[F]): Users[F] = U
}

sealed abstract class UsersInstances {
  implicit def replicated[F[_]: Monad: EntityDecoder[?[_], String]](
      db: Database[F],
      daemon: GossipDaemon[F]
  ): Users[F] = new Users[F] {
    def addUser(name: String): F[User] =
      for {
        id <- daemon.getUniqueId
        user = User(id, name)
        _ <- daemon.send[UsersAction](AddUser(user))
      } yield user

    def searchUser(name: String): F[List[User]] =
      db.query[User](sql"""
         SELECT *
         FROM users
         WHERE name = $name
       """)

    def getUser(id: String): F[Option[User]] = ???
  }
}

object UsersActions {
  sealed trait UsersAction
  case class AddUser(user: User) extends UsersAction
  //case class RemoveUser(user: User) extends UsersAction

  implicit val namedUsersAction: EventTypable[UsersAction] = new EventTypable[UsersAction] {
    val eventType: String = "Users"
  }

  implicit val replicableUsersAction: Replicable[UsersAction] =
    new Replicable[UsersAction] {
      def replicate[F[_]: Effect](db: Database[F]): UsersAction => F[Unit] = {
        case AddUser(user) =>
          db.insert(sql"""
           INSERT INTO users (name, password)
           VALUES (${user.id}, ${user.name})
       """)
      }
    }
}
