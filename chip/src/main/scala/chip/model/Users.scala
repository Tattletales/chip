package chip.model

import cats.Monad
import cats.effect.Effect
import cats.implicits._
import doobie.implicits._
import backend.events.Subscriber.{EventType, EventTypeTag}
import chip.events.Replicable
import chip.model.User.{UserId, Username}
import chip.model.UsersEvents.{AddUser, UsersEvent}
import backend.gossip.GossipDaemon
import org.http4s.EntityDecoder
import backend.storage.Database
import shapeless.tag
import chip.implicits._
import backend.events.EventTyper
import io.circe.generic.auto._
import backend.implicits._
import gossip.Gossipable

/**
  * Users DSL
  */
trait Users[F[_]] {
  def addUser(name: Username): F[User]
  def searchUser(name: Username): F[List[User]]
  def getUser(id: UserId): F[Option[User]]
}

object Users {
  /* ------ Interpreters ------ */

  /**
    * Interprets to [[Database]] and [[GossipDaemon]] DSLs.
    *
    * Replicates the events.
    */
  def replicated[F[_]: Monad: EntityDecoder[?[_], String], E: Gossipable](
      db: Database[F],
      daemon: GossipDaemon[F, E]
  ): Users[F] = new Users[F] {
    def addUser(name: Username): F[User] =
      for {
        id <- daemon.getNodeId
        user = User(id, name)
        _ <- daemon.send[UsersEvent](AddUser(user))
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

object UsersEvents {
  sealed trait UsersEvent
  case class AddUser(user: User) extends UsersEvent

  object UsersEvent {
    implicit val namedUsersEvent: EventTyper[UsersEvent] = new EventTyper[UsersEvent] {
      val eventType: EventType = tag[EventTypeTag][String]("Users")
    }

    implicit val replicableUsersEvent: Replicable[UsersEvent] =
      new Replicable[UsersEvent] {
        def replicate[F[_]: Effect](db: Database[F]): UsersEvent => F[Unit] = {
          case AddUser(user) =>
            db.insert(sql"""
           INSERT INTO users (id, name)
           VALUES (${user.id}, ${user.name})
       """)
        }
      }
  }
}
