package vault

import backend.events.Subscriber
import backend.gossip.GossipDaemon
import backend.gossip.Node.NodeIdTag
import backend.network.HttpClient
import backend.network.HttpClient.RootTag
import backend.storage.KVStore
import cats.Applicative
import cats.effect.{Effect, IO}
import doobie.util.transactor.Transactor
import vault.events.Transactions.handleTransactionStages
import fs2.StreamApp.ExitCode
import fs2._
import vault.model.Account.{Money, User}
import vault.model.Accounts
import org.http4s.client.blaze.Http1Client
import shapeless.tag
import vault.api.Server

import scala.concurrent.ExecutionContext.Implicits.global

object VaultApp extends Vault[IO]

class Vault[F[_]: Effect] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      for {
        client <- Http1Client.stream()
        httpClient = HttpClient.default(client)

        daemon0 = GossipDaemon.default[F](tag[RootTag][String]("http://localhost:59234"))(
            httpClient,
            Subscriber.serverSentEvent)
        daemon = GossipDaemon.logging("test")(daemon0)

        kvs = KVStore.mapKVS[F, User, Money]
        
        accounts <- Stream.eval(
          Accounts
            .default[F](daemon, kvs)
            .withAccounts(tag[NodeIdTag][String]("MyOwnKey"),
                          tag[NodeIdTag][String]("alice"),
                          tag[NodeIdTag][String]("bob")))

        handler = daemon.subscribe.through(handleTransactionStages(_ => implicitly[Applicative[F]].unit)(daemon, kvs, accounts))

        //server = Server.authed(accounts, daemon).run

        ec <- Stream(handler).join(2).drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

  val xa: Transactor[F] = Transactor.fromDriverManager[F](
    "org.postgresql.Driver", // driver classname
    "jdbc:postgresql:chip_db", // connect URL (driver-specific)
    "florian", // user
    "mJ9da5mPHniKrsr8KeYx" // password
  )
}
