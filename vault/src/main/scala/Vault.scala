package vault

import backend.events.WSEvent
import cats.effect.{Effect, IO, Timer}
import cats.implicits._
import io.circe.generic.auto._
import io.circe.refined._
import fs2.StreamApp.ExitCode
import fs2._
import backend.gossip._
import Node.NodeIdTag
import backend.storage._
import backend.network._
import backend.events._
import vault.model.{Money, User}
import org.http4s.circe._
import vault.events.{TransactionStage, TransactionsHandler}
import backend.implicits._
import vault.api.Server
import vault.model._
import cats.data.NonEmptyList
import eu.timepit.refined.api.RefType.applyRef
import config._
import eu.timepit.refined.auto._
import shapeless.tag
import vault.benchmarks.Benchmark

import scala.concurrent.ExecutionContext.Implicits.global

object VaultApp extends Vault[IO]

class Vault[F[_]: Timer: Effect] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      val nodeNumber = args.headOption
        .map(_.toInt)
        .getOrElse(throw new IllegalArgumentException("The node number needs to be provided!"))

      val conf = if (args.length == 1) {
        VaultConfig.config
      } else {
        VaultConfig.config
          .copy(nodeIds = NonEmptyList.fromListUnsafe(args.tail.map(tag[NodeIdTag][String])))
      }

      val nodeId = conf.nodeIds
        .get(nodeNumber)
        .getOrElse(
          throw new IllegalArgumentException(s"No node id corresponding to node #$nodeNumber."))

      val webSocketRouteWithNodeId =
        applyRef[Route](conf.webSocketRoute.value ++ s"/$nodeId").right.get
      val nodeIdRouteWithNodeId = applyRef[Route](conf.nodeIdRoute.value ++ s"/$nodeId").right.get
      val logRouteWithNodeId = applyRef[Route](conf.logRoute.value ++ s"/$nodeId").right.get

      for {
        httpClient <- httpClient

        wsClient <- Stream.eval(
          webSocketClient[F, TransactionStage, WSEvent](webSocketRouteWithNodeId))

        daemon = gossipDaemon(nodeIdRouteWithNodeId, logRouteWithNodeId)(nodeId)(httpClient, wsClient)

        loggingDaemon = conf.logFile match {
          case Some(path) => logToFile(path)(daemon)
          case None       => daemon
        }

        kvs = keyValueStore[F, User, Money]

        accounts <- Stream.eval(accounts(conf.nodeIds)(loggingDaemon, kvs))

        program = conf.benchmark match {
          case Some(benchmark) =>
            Benchmark[F, WSEvent](benchmark)(conf.nodeIds)(kvs, accounts, loggingDaemon).run
          case None =>
            val server = Server(accounts, loggingDaemon, 8080 + nodeNumber).run.map(_ => ())
            val handler = TransactionsHandler(loggingDaemon, kvs, accounts).run

            Stream[Stream[F, Unit]](server, handler).join[F, Unit](2)
        }

        ec <- program.drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

}
