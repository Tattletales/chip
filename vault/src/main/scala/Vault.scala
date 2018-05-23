package vault

import backend.events.Subscriber
import backend.events.Subscriber.{Event, EventId}
import cats.effect.{Effect, IO}
import doobie.util.transactor.Transactor
import fs2.StreamApp.ExitCode
import fs2._
import backend.gossip.GossipDaemon
import backend.gossip.Node._
import backend.storage.{Database, KVStore}
import vault.model.Account.{Money, MoneyTag, User}
import shapeless.tag
import org.http4s.circe._
import vault.events.TransactionStage
import vault.events.Transactions.handleTransactionStages
import vault.implicits._
import backend.implicits._
import backend.network.HttpClient
import backend.network.HttpClient.RootTag
import org.http4s.client.blaze.Http1Client
import vault.api.Server
import vault.model.Accounts
import fs2.io._
import java.nio.file.Paths

import cats.Applicative

import scala.concurrent.ExecutionContext.Implicits.global

object VaultApp extends Vault[IO]

class Vault[F[_]: Effect] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      val nodes = args.headOption.map(_.toInt)
      val nodeIds = nodes.map(ns => args.slice(1, 1 + ns).map(tag[NodeIdTag][String])).get
      val frontend_port = nodes.map {ns =>
        if (args.length > ns + 1)
          args(ns + 1)
        else "8080"
      }.map(_.toInt)

      for {
        client <- Http1Client.stream()
        httpClient = HttpClient.default(client)

        daemon0 = GossipDaemon.default[F](
          tag[RootTag][String]("http://localhost:59234"),
          nodeIds.headOption)(httpClient, Subscriber.serverSentEvent)
        daemon = GossipDaemon.logging("test")(daemon0)

        kvs = KVStore.mapKVS[F, User, Money]

        accounts <- Stream.eval(
          Accounts
            .default[F](daemon, kvs)
            .withAccounts(nodeIds.head, nodeIds.tail: _*))

        handler = daemon.subscribe.through(
          handleTransactionStages(_ => implicitly[Applicative[F]].unit)(daemon, kvs, accounts))

        server = Server.authed(accounts, daemon, frontend_port).run

        ec <- Stream(server, handler).join(2).drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

  val xa: Transactor[F] = Transactor.fromDriverManager[F](
    "org.postgresql.Driver", // driver classname
    "jdbc:postgresql:chip_db", // connect URL (driver-specific)
    "florian", // user
    "mJ9da5mPHniKrsr8KeYx" // password
  )
}
