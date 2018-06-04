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
import backend.implicits._
import backend.events._
import vault.model.{Money, User}
import org.http4s.circe._
import vault.events.{EventsHandler, TransactionStage}
import vault.implicits._
import backend.implicits._
import vault.api.Server
import vault.model._
import cats.data.NonEmptyList
import eu.timepit.refined.api.RefType.applyRef
import eu.timepit.refined.pureconfig._
import config._
import pureconfig._
import pureconfig.module.cats._
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
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
        loadConfigOrThrow[VaultConfig]("vault")
      } else {
        loadConfigOrThrow[VaultConfig]("vault")
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

        accounts <- Stream.eval(accounts(conf.nodeIds)(loggingDaemon, kvs))

        program = conf.benchmark match {
          case Some(benchmark) =>
            Benchmark[F, WSEvent](benchmark)(conf.nodeIds)(kvs, accounts, loggingDaemon).run
          case None =>
            Stream[Stream[F, Unit]](
              Server.authed(accounts, loggingDaemon, Some(frontendPort)).run.map(_ => ()),
              EventsHandler(loggingDaemon, kvs, accounts).run).join[F, Unit](2)
        }

        ec <- program.drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

}
