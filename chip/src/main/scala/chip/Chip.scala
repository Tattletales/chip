package chip

import backend.events.{PayloadTag, WSEvent}
import backend.gossip.Node.NodeIdTag
import backend.gossip._
import backend.implicits._
import backend.network._
import backend.network.Route
import backend.storage._
import cats.data.NonEmptyList
import cats.effect.{Effect, IO, Timer}
import cats.implicits._
import chip.api.Server
import chip.events.Replicator
import chip.model.TweetsEvents.TweetsEvent._
import chip.model.UsersEvents.UsersEvent._
import chip.model.{Tweets, Users}
import doobie.util.transactor.Transactor
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import fs2.StreamApp.ExitCode
import fs2._
import org.http4s.circe._
import shapeless.tag
import backend.events.EventTypable.ops._
import chip.events.ReplicateEvents.Event

import scala.concurrent.ExecutionContext.Implicits.global

object ChipApp extends Chip[IO]

class Chip[F[_]: Effect: Timer] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler[F](corePoolSize = 10).flatMap { implicit S =>
      val nodeNumber = args.headOption
        .map(_.toInt)
        .getOrElse(throw new IllegalArgumentException("The node number needs to be provided!"))

      val nodeIds = NonEmptyList.fromListUnsafe(args.tail.map(tag[NodeIdTag][String]))

      val logRoute: Route = ???
      val nodeIdRoute: Route = ???
      val wsRoute: Route = ???

      val db = database(xa)

      for {
        httpClient <- httpClient
        wsClient <- Stream.eval(webSocketClient[F, Event, WSEvent](wsRoute))

        daemon = gossipDaemon(nodeIdRoute, logRoute)(nodeIds.head)(httpClient, wsClient)

        users = Users.replicated[F, WSEvent](
          db,
          daemon.lmap(usersEvent =>
            Event(usersEvent.eventType, tag[PayloadTag][Json](usersEvent.asJson))))

        tweets = Tweets.replicated[F, WSEvent](
          db,
          daemon.lmap(tweetsEvent =>
            Event(tweetsEvent.eventType, tag[PayloadTag][Json](tweetsEvent.asJson))))

        replicator = Replicator[F](db, daemon.rmap(_.payload.as[Event].right.get).subscribe)

        server = Server.authed(users, tweets, daemon).run.map(_ => ())

        ec <- Stream[Stream[F, Unit]](replicator, server).join[F, Unit](2).drain ++ Stream.emit(
          ExitCode.Success)
      } yield ec
    }

  val xa: Transactor[F] = Transactor.fromDriverManager[F](
    "org.postgresql.Driver", // driver classname
    "jdbc:postgresql:chip_db", // connect URL (driver-specific)
    "florian", // user
    "mJ9da5mPHniKrsr8KeYx" // password
  )
}
