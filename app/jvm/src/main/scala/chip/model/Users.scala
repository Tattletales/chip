package chip.model

import cats.Monad
import cats.effect.Effect
import cats.implicits._
import doobie.implicits._
import backend.events.Subscriber.{EventType, EventTypeTag}
import chip.events.Replicable
import chip.model.User.{UserId, Username}
import chip.model.UsersActions.{AddUser, UsersAction}
import backend.gossip.GossipDaemon
import org.http4s.EntityDecoder
import backend.storage.Database
import shapeless.tag
import chip.implicits._
import backend.events.EventTyper
import io.circe.generic.auto._
import backend.implicits._

trait Users[F[_]] {
  def addUser(name: Username): F[User]
  def searchUser(name: Username): F[List[User]]
  def getUser(id: UserId): F[Option[User]]
}

object Users extends UsersInstances {
  def apply[F[_]](implicit U: Users[F]): Users[F] = U
}

sealed abstract class UsersInstances {
  implicit def replicated[F[_]: Monad: EntityDecoder[?[_], String]](
      db: Database[F],
      daemon: GossipDaemon[F]
  ): Users[F] = new Users[F] {
    def addUser(name: Username): F[User] =
      for {
        id <- daemon.getNodeId
        user = User(id, name)
        _ <- daemon.send[UsersAction](AddUser(user))
      } yield user

    def searchUser(name: Username): F[List[User]] =
      db.query[User](sql"""
         SELECT *
         FROM users
         WHERE name = $name
       """)

    def getUser(id: UserId): F[Option[User]] = db.query[User](sql"""
         SELECT *
         FROM users
         WHERE id = $id
       """).map(_.headOption)
  }
}

object UsersActions {
  sealed trait UsersAction
  case class AddUser(user: User) extends UsersAction

  object UsersAction {
    implicit val namedUsersAction: EventTyper[UsersAction] = new EventTyper[UsersAction] {
      val eventType: EventType = tag[EventTypeTag][String]("Users")
    }

    implicit val replicableUsersAction: Replicable[UsersAction] =
      new Replicable[UsersAction] {
        def replicate[F[_]: Effect](db: Database[F]): UsersAction => F[Unit] = {
          case AddUser(user) =>
            db.insert(sql"""
           INSERT INTO users (id, name)
           VALUES (${user.id}, ${user.name})
       """)
        }
      }
  }
}
