package vault

import backend.errors.MalformedUriError
import backend.events.WSEvent
import cats.effect.{Effect, IO, Timer}
import cats.implicits._
import io.circe.generic.auto._
import io.circe.refined._
import fs2.StreamApp.ExitCode
import fs2._
import backend.gossip._
import backend.gossip.Node._
import backend.storage._
import backend.network._
import backend.events._
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
import vault.model._
import cats.{Applicative, MonadError}
import cats.data.NonEmptyList
import eu.timepit.refined.refineV
import eu.timepit.refined.api.RefType.applyRef
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

      def checkUri(uri: String)(implicit F: MonadError[F, Throwable]): F[Uri] =
        F.fromEither(applyRef[Uri](uri).left.map(m => MalformedUriError(uri, m)))

      val uncheckedWsRoute = s"ws://localhost:59234/events/${nodeIds.head}"
      val uncheckedNodeIdRoute = s"http://localhost:59234/unique/${nodeIds.head}"
      val uncheckedLogRoute = s"http://localhost:59234/log/${nodeIds.head}"

      for {
        httpClient <- httpClient

        wsClient <- Stream.eval(
          checkUri(uncheckedWsRoute).flatMap(webSocketClient[F, TransactionStage, WSEvent]))

        daemon <- Stream.eval((checkUri(uncheckedNodeIdRoute), checkUri(uncheckedLogRoute)).mapN {
          case (nodeIdRoute, logRoute) =>
            gossipDaemon(nodeIdRoute, logRoute)(nodeIds.headOption)(httpClient, wsClient)
        })

        loggingDaemon = logToFile("test")(daemon)

        kvs = keyValueStore[F, User, Money]

        accounts <- Stream.eval(
          accounts(loggingDaemon, kvs).withAccounts(NonEmptyList.fromListUnsafe(nodeIds)))

        handler = daemon.subscribe.through(handleTransactionStages(_ =>
          implicitly[Applicative[F]].unit)(loggingDaemon, kvs, accounts))

        server = Server.authed(accounts, loggingDaemon, frontend_port).run
        //server = Benchmark.random(nodeIds)(kvs, accounts, daemon).run

        ec <- Stream(server, handler).join(2).drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

}
