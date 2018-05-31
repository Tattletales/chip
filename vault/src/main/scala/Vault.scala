package vault

import backend.errors.MalformedUriError
import backend.events.WSEvent
import cats.effect.{Effect, IO, Timer}
import io.circe.generic.auto._
import io.circe.refined._
import fs2.StreamApp.ExitCode
import fs2._
import backend.gossip.GossipDaemon
import backend.gossip.Node._
import backend.storage.KVStore
import vault.model.{Money, User}
import shapeless.tag
import org.http4s.circe._
import vault.events.TransactionStage
import vault.events.Transactions.handleTransactionStages
import vault.implicits._
import backend.implicits._
import backend.network.HttpClient
import org.http4s.client.blaze.Http1Client
import vault.api.Server
import vault.model.Accounts
import cats.{Applicative, MonadError}
import cats.data.NonEmptyList
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Uri
import eu.timepit.refined.refineV
import backend.network.WebSocketClient

import scala.concurrent.ExecutionContext.Implicits.global

object VaultApp extends Vault[IO]

class Vault[F[_]: Timer: Effect] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      val nodes = args.headOption.map(_.toInt)
      val nodeIds = nodes.map(ns => args.slice(1, 1 + ns).map(tag[NodeIdTag][String])).get
      val frontend_port = nodes
        .map { ns =>
          if (args.length > ns + 1)
            args(ns + 1)
          else "8080"
        }
        .map(_.toInt)

      def checkUri(uri: String)(implicit F: MonadError[F, Throwable]): F[String Refined Uri] =
        F.fromEither(refineV[Uri](uri).left.map(m => MalformedUriError(uri, m)))

      val uncheckedWsRoute = s"ws://localhost:59234/events/${nodeIds.head}"
      val uncheckedNodeIdRoute = s"http://localhost:59234/unique/${nodeIds.head}"
      val uncheckedLogRoute = s"http://localhost:59234/log/${nodeIds.head}"

      for {
        client <- Http1Client.stream()
        httpClient = HttpClient.default(client)

        incoming <- Stream.eval(async.unboundedQueue[F, WSEvent])
        outgoing <- Stream.eval(async.unboundedQueue[F, TransactionStage])

        wsRoute <- Stream.eval(checkUri(uncheckedWsRoute))

        wsClient = WebSocketClient.akkaHttp[F, TransactionStage, WSEvent](wsRoute)(incoming,
                                                                                   outgoing)

        nodeIdRoute <- Stream.eval(checkUri(uncheckedNodeIdRoute))
        logRoute <- Stream.eval(checkUri(uncheckedLogRoute))

        daemon0 = GossipDaemon.webSocket[F, TransactionStage](nodeIdRoute, logRoute)(
          nodeIds.headOption)(httpClient, wsClient)
        daemon = GossipDaemon.logging[F, TransactionStage, WSEvent]("test")(daemon0)

        kvs = KVStore.mutableMap[F, User, Money]

        accounts <- Stream.eval(
          Accounts
            .default[F, WSEvent](daemon, kvs)
            .withAccounts(NonEmptyList.fromListUnsafe(nodeIds)))

        handler = daemon.subscribe.through(
          handleTransactionStages(_ => implicitly[Applicative[F]].unit)(daemon, kvs, accounts))

        server = Server.authed(accounts, daemon, frontend_port).run
        //server = Benchmark.random(nodeIds)(kvs, accounts, daemon).run

        ec <- Stream(server, handler).join(2).drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

}
