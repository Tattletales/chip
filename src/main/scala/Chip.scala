import HttpClient.Uri
import Tweet._
import TweetsActions._
import User.{Name, Password, _}
import UsersActions._
import cats.data.OptionT
import cats.effect.{Effect, IO}
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

  val userDB = Database.doobieDatabase(xa)
  val tweetsDB = Database.doobieDatabase(xa)

  val httpClient: HttpClient[Stream[F, ?], F] = HttpClient.http4sClient[F]

  val usersActionsDistributor =
    Distributor.gossip[Stream[F, ?], F, UsersAction](Uri("localhost"), httpClient)
  val tweetsActionsDistributor =
    Distributor.gossip[Stream[F, ?], F, TweetsAction](Uri("localhost"), httpClient)

  val users = Users.replicated(userDB, usersActionsDistributor)
  val tweets = Tweets.replicated(tweetsDB, tweetsActionsDistributor)

  val repo = Repo[F](users, tweets)

  val sseClient = SseClient[Stream[F, ?]]

  val replicator = Replicator[F](repo, sseClient.subscribe("Bla"))

  val program = (for {
    user <- OptionT(users.addUser(Name("Teub"), Password("admin")))
    tweet <- OptionT(tweets.addTweet(user, Tweet(TweetId(0), UserId(0), TweetContent("Ma bite"))))
  } yield tweet).value

}
