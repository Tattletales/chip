package gossipServer

import backend.events.WSEvent
import backend.gossip.Node.{NodeId, NodeIdTag}
import backend.storage.KVStore
import backend.implicits._
import cats.data.NonEmptyList
import cats.effect.{Effect, IO}
import cats.implicits._
import fs2.StreamApp.ExitCode
import fs2.{Stream, StreamApp, async}
import org.http4s.server.blaze.BlazeBuilder
import eu.timepit.refined.pureconfig._
import pureconfig._
import pureconfig.module.cats._
import eu.timepit.refined.auto._
import eu.timepit.refined.pureconfig._
import gossipServer.config.ServerConfig
import shapeless.tag

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

object ServerApp extends Server[IO]

class Server[F[_]: Effect] extends StreamApp[F] {
  override def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] = {
    val conf = if (args.isEmpty) {
      loadConfigOrThrow[ServerConfig]("server")
    } else {
      ServerConfig(
        NonEmptyList.fromListUnsafe(args.slice(1, args.head.toInt + 1).map(tag[NodeIdTag][String])))
    }

    val eventQueues = conf.nodeIds
      .traverse(nodeId => async.unboundedQueue[F, WSEvent].map((nodeId, _)))
      .map(_.toList.toMap)

    val eventIds =
      conf.nodeIds.traverse(nodeId => async.refOf[F, Int](0).map((nodeId, _))).map(_.toList.toMap)

    for {
      eventQueues <- Stream.eval(eventQueues)
      eventIds <- Stream.eval(eventIds)
      store = KVStore.mutableMap[F, NodeId, List[WSEvent]]
      _ <- Stream.eval(conf.nodeIds.traverse(store.put(_, List.empty)))
      service = GossipServer.webSocket(eventQueues, eventIds, store).service

      server <- BlazeBuilder[F]
        .withIdleTimeout(Duration.Inf)
        .withWebSockets(true)
        .bindHttp(59234, "localhost")
        .mountService(service, "/")
        .serve
    } yield server
  }

}
