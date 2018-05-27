package gossipServer

import backend.events.Subscriber.WSEvent
import backend.gossip.Node.{NodeId, NodeIdTag}
import backend.storage.KVStore
import cats.effect.{Effect, IO}
import cats.implicits._
import fs2.StreamApp.ExitCode
import fs2.{Scheduler, Stream, StreamApp, async}
import io.circe.Json
import org.http4s.ServerSentEvent
import org.http4s.server.blaze.BlazeBuilder
import shapeless.tag

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

object ServerApp extends Server[IO]

class Server[F[_]: Effect] extends StreamApp[F] {
  override def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] = {
    val nodes = args(0).toInt
    val nodeNames = args.slice(1, 1 + nodes).map(tag[NodeIdTag][String])

    val eventQueues = nodeNames
      .traverse(_ => async.unboundedQueue[F, WSEvent])
      .map(nodeNames.zip(_).toMap)

    val eventIds = nodeNames
      .traverse(_ => async.refOf[F, Int](0))
      .map(nodeNames.zip(_).toMap)

    for {
      eventQueues <- Stream.eval(eventQueues)
      eventIds <- Stream.eval(eventIds)
      store = KVStore.mutableMap[F, NodeId, List[WSEvent]]
      _ <- Stream.eval(nodeNames.traverse(store.put(_, List.empty)))
      service = GossipServer.webSocket(nodeNames)(eventQueues, eventIds, store).service

      server <- BlazeBuilder[F]
        .withIdleTimeout(Duration.Inf)
        .withWebSockets(true)
        .bindHttp(59234, "localhost")
        .mountService(service, "/")
        .serve
    } yield server
  }

}
