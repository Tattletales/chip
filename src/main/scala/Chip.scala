import Subscriber.Event
import cats.effect.{Effect, IO}
import cats.~>
import doobie.util.transactor.Transactor
import fs2.StreamApp.ExitCode
import io.circe.Json
import fs2._

import scala.concurrent.ExecutionContext.Implicits.global

object ChipApp extends Chip[IO]

class Chip[F[_]: Effect] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      for {
        eventQueue <- Stream.eval(async.unboundedQueue[F, Event])
        counter <- Stream.eval(async.Ref[F, Int](0))

        db: Database[F] = Database.doobieDatabase[F](xa)

        daemon = GossipDaemon.mock[F](eventQueue, counter)

        users = Users.replicated[Stream[F, ?], F](db, daemon)
        tweets = Tweets.replicated[Stream[F, ?], F](db, daemon)

        replicator = Replicator[F](db, daemon.subscribe)

        // Program
        user = users.addUser("Tattletales")
        // ---

        ec <- Stream(user.map(_ => ()), replicator).join(2).drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

  val xa: Transactor[F] = Transactor.fromDriverManager[F](
    "org.postgresql.Driver", // driver classname
    "jdbc:postgresql:chip_db", // connect URL (driver-specific)
    "tattletales", // user
    "tattletales" // password
  )

  implicit val fToStream: F ~> Stream[F, ?] = new (F ~> Stream[F, ?]) {
    def apply[A](fa: F[A]): Stream[F, A] = Stream.eval(fa)
  }
}
