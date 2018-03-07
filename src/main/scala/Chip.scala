import TweetsActions._
import UsersActions._
import org.http4s.circe._
import cats.effect.{Effect, IO}
import cats.~>
import doobie.util.transactor.Transactor
import fs2.StreamApp.ExitCode
import io.circe.generic.auto._
import fs2._

import scala.concurrent.ExecutionContext.Implicits.global

object ChipApp extends Chip[IO]

class Chip[F[_]: Effect] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      Stream(program.map(_ => ()), replicator).join(2).drain ++ Stream.emit(ExitCode.Success)
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

  val db: Database[F] = Database.doobieDatabase[F](xa)

  val httpClient = HttpClient.http4sClient[F]
  val daemon = GossipDaemon.localhost[Stream[F, ?], F](httpClient)

  val usersActionsDistributor =
    Distributor.gossip[Stream[F, ?], F, UsersAction]("localhost", httpClient)

  val tweetsActionsDistributor =
    Distributor.gossip[Stream[F, ?], F, TweetsAction]("localhost", httpClient)

  val users = Users.replicated[Stream[F, ?], F](db, usersActionsDistributor, daemon)
  val tweets = Tweets.replicated[Stream[F, ?], F](db, tweetsActionsDistributor, daemon)

  val sseClient = SseClient[Stream[F, ?]]

  val replicator = Replicator[F](db, sseClient.subscribe("Bla"))

  val program = for {
    user <- users.addUser("Tattletales")
    //tweet <- OptionT(tweets.addTweet(user, Tweet(TweetId(0), UserId(0), TweetContent("Hello World"))))
  } yield user

}
