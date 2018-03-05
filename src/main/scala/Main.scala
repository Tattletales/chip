import TweetsActions.TweetsAction
import UsersActions.UsersAction
import cats.effect.{Effect, IO}
import doobie.util.transactor.Transactor
import fs2._
import fs2.StreamApp.ExitCode
import HandleEvents._
import shapeless.{HNil, ::}
import org.http4s

object Main extends StreamApp[IO] {
  def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, ExitCode] = ???

  type Query = String

  val xa: Transactor[IO] = ???

  val userDB: Database[Stream[IO, ?]] = Database.doobieDatabase(xa)
  val tweetsDB: Database[Stream[IO, ?]] = Database.doobieDatabase(xa)

  val usersActionsDistributor: Distributor[Stream[IO, ?], UsersAction] = ???
  val tweetsActionsDistributor: Distributor[Stream[IO, ?], TweetsAction] = ???

  val users: Users[Stream[IO, ?]] = Users.replicated(userDB, usersActionsDistributor)
  val tweets: Tweets[Stream[IO, ?]] = Tweets.replicated[IO](tweetsDB, tweetsActionsDistributor)

  val repo: Repo[IO] = Repo[IO](users, tweets)

  val sseClient: SseClient[Stream[IO, ?]] = SseClient[Stream[IO, ?]]

  val replicator: Stream[IO, Unit] =
    Replicator[IO, UsersAction :: TweetsAction](repo, sseClient.subscribe("Bla"))
}
