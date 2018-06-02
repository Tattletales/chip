package vault

import backend.events.WSEvent
import cats.effect.{Effect, IO, Timer}
import cats.implicits._
import io.circe.generic.auto._
import io.circe.refined._
import fs2.StreamApp.ExitCode
import fs2._
import backend.gossip._
import backend.storage._
import backend.network._
import backend.implicits._
import backend.events._
import vault.model.{Money, User}
import org.http4s.circe._
import vault.events.TransactionStage
import vault.events.Transactions.handleTransactionStages
import vault.implicits._
import backend.implicits._
import vault.api.Server
import vault.model._
import cats.Applicative
import eu.timepit.refined.api.RefType.applyRef
import eu.timepit.refined.pureconfig._
import config._
import pureconfig._
import pureconfig.module.cats._
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import vault.programs.Benchmark

import scala.concurrent.ExecutionContext.Implicits.global

object VaultApp extends Vault[IO]

class Vault[F[_]: Timer: Effect] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      if (args.isEmpty) throw new IllegalArgumentException("The node number needs to be provided!")

      val nodeNumber = args.headOption
        .map(_.toInt)
        .getOrElse(throw new IllegalArgumentException("The node number needs to be provided!"))

      val conf = loadConfigOrThrow[VaultConfig]("vault")

      val nodeId = conf.nodeIds
        .get(nodeNumber)
        .getOrElse(
          throw new IllegalArgumentException(s"No node id corresponding to node #$nodeNumber."))

      val webSocketRouteWithNodeId =
        applyRef[Route](conf.webSocketRoute.value ++ s"/$nodeId").right.get
      val nodeIdRouteWithNodeId = applyRef[Route](conf.nodeIdRoute.value ++ s"/$nodeId").right.get
      val logRouteWithNodeId = applyRef[Route](conf.logRoute.value ++ s"/$nodeId").right.get

      val frontendPort = 8080 + nodeNumber

      for {
        httpClient <- httpClient

        wsClient <- Stream.eval(
          webSocketClient[F, TransactionStage, WSEvent](webSocketRouteWithNodeId))

        daemon = gossipDaemon(nodeIdRouteWithNodeId, logRouteWithNodeId)(
          conf.nodeIds
            .get(nodeNumber)
            .getOrElse(throw new IllegalArgumentException(
              s"No node id corresponding to node #$nodeNumber.")))(httpClient, wsClient)

        loggingDaemon = logToFile("test")(daemon)

        kvs = keyValueStore[F, User, Money]

        accounts <- Stream.eval(accounts(loggingDaemon, kvs).withAccounts(conf.nodeIds))

        handler = daemon.subscribe.through(handleTransactionStages(_ =>
          implicitly[Applicative[F]].unit)(loggingDaemon, kvs, accounts))

        program = conf.benchmark match {
          case Some(benchmark) =>
            Benchmark[F, WSEvent](benchmark)(conf.nodeIds)(kvs, accounts, loggingDaemon).run
          case None => Server.authed(accounts, loggingDaemon, Some(frontendPort)).run
        }

        ec <- Stream(program, handler).join(2).drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

}
