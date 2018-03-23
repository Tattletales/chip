package chip

import _root_.events.Subscriber.{Event, EventId, NodeId, NodeIdTag}
import cats.effect.{Effect, IO}
import chip.api.Server
import chip.events.Replicator
import chip.model.{Tweets, Users}
import doobie.util.transactor.Transactor
import fs2.StreamApp.ExitCode
import fs2._
import gossip.GossipDaemon
import storage.Database
import shapeless.tag
import org.http4s.circe._

import scala.concurrent.ExecutionContext.Implicits.global

object ChipApp extends Chip[IO]

class Chip[F[_]: Effect] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      for {
        eventQueue <- Stream.eval(async.unboundedQueue[F, Event])
        counter <- Stream.eval(async.Ref[F, NodeId](tag[NodeIdTag][String]("aaa")))
        vClock <- Stream.eval(async.Ref[F, Map[NodeId, EventId]](Map.empty))

        db: Database[F] = Database.doobieDatabase[F](xa)

        daemon = GossipDaemon.mock[F](eventQueue, counter)

        users = Users.replicated[F](db, daemon)
        tweets = Tweets.replicated[F](db, daemon)

        replicator = Replicator[F](vClock, db, daemon.subscribe)

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
