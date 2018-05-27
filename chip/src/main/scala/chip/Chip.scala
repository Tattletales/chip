package chip

import backend.events.Subscriber.SSEvent
import backend.gossip.GossipDaemon
import backend.storage.Database
import cats.effect.{Effect, IO}
import cats.implicits._
import chip.api.Server
import chip.events.Replicator
import chip.model.ChipEvent.{ChipEvent, _}
import chip.model.TweetsEvents.TweetsEvent
import chip.model.TweetsEvents.TweetsEvent._
import chip.model.UsersEvents.UsersEvent
import chip.model.UsersEvents.UsersEvent._
import chip.model.{Tweets, Users}
import doobie.util.transactor.Transactor
import fs2.StreamApp.ExitCode
import fs2._
import org.http4s.circe._
import shapeless.tag

import scala.concurrent.ExecutionContext.Implicits.global

object ChipApp extends Chip[IO]

class Chip[F[_]: Effect] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      for {
        eventQueue <- Stream.eval(async.unboundedQueue[F, SSEvent])

        db: Database[F] = Database.doobieDatabase[F](xa)
        
        daemon = GossipDaemon.sseMock[F, ChipEvent](eventQueue)

        users = Users.replicated[F, SSEvent](db, daemon.lmap(Right.apply[TweetsEvent, UsersEvent]))
        tweets = Tweets.replicated[F, SSEvent](db, daemon.lmap(Left.apply[TweetsEvent, UsersEvent]))

        replicator = Replicator[F, SSEvent](db, daemon.subscribe)

        server = Server.authed(users, tweets, daemon).run

        ec <- Stream(replicator, server).join(2).drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

  val xa: Transactor[F] = Transactor.fromDriverManager[F](
    "org.postgresql.Driver", // driver classname
    "jdbc:postgresql:chip_db", // connect URL (driver-specific)
    "florian", // user
    "mJ9da5mPHniKrsr8KeYx" // password
  )
}
