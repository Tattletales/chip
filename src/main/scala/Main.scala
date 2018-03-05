import HttpClient.Uri
import TweetsActions._
import UsersActions._
import cats.effect.IO
import doobie.util.transactor.Transactor
import fs2.StreamApp.ExitCode
import fs2._
import org.http4s.client.DisposableResponse

object Main extends StreamApp[IO] {
  def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, ExitCode] = ???

  type Query = String

  val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
    "org.postgresql.Driver", // driver classname
    "jdbc:postgresql:chip_db", // connect URL (driver-specific)
    "tattletales", // user
    "tattletales" // password
  )

  val userDB: Database[Stream[IO, ?]] = Database.doobieDatabase(xa)
  val tweetsDB: Database[Stream[IO, ?]] = Database.doobieDatabase(xa)

  val httpClient: HttpClient[Stream[IO, ?], IO] = HttpClient.http4sClient[IO]

  val usersActionsDistributor: Distributor[Stream[IO, ?], IO, UsersAction] =
    Distributor.gossip[Stream[IO, ?], IO, UsersAction](Uri("localhost"), httpClient)
  val tweetsActionsDistributor: Distributor[Stream[IO, ?], IO, TweetsAction] =
    Distributor.gossip[Stream[IO, ?], IO, TweetsAction](Uri("localhost"), httpClient)

  val users: Users[Stream[IO, ?]] = Users.replicated(userDB, usersActionsDistributor)
  val tweets: Tweets[Stream[IO, ?]] = Tweets.replicated[IO](tweetsDB, tweetsActionsDistributor)

  val repo: Repo[IO] = Repo[IO](users, tweets)

  val sseClient: SseClient[Stream[IO, ?]] = SseClient[Stream[IO, ?]]

  val replicator: Stream[IO, Unit] =
    Replicator[IO](repo, sseClient.subscribe("Bla"))
}
