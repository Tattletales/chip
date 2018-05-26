package gossipServer

import backend.gossip.Node.{NodeId, NodeIdTag}
import backend.storage.KVStore
import cats.effect.IO
import cats.implicits._
import fs2.StreamApp.ExitCode
import fs2.{Scheduler, Stream, StreamApp, async}
import org.http4s.ServerSentEvent
import org.http4s.server.blaze.BlazeBuilder
import shapeless.tag

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration

object MainApp extends Main

class Main extends StreamApp[IO] {
  override def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, ExitCode] = {
    val nodes = args(0).toInt
    val nodeNames = args.slice(1, 1 + nodes).map(tag[NodeIdTag][String])

    val eventQueues = nodeNames
      .traverse(_ => async.unboundedQueue[IO, ServerSentEvent])
      .map(nodeNames.zip(_).toMap)

    val eventIds = nodeNames
      .traverse(_ => async.refOf[IO, Int](0))
      .map(nodeNames.zip(_).toMap)

    for {
      eventQueues <- Stream.eval(eventQueues)
      eventIds <- Stream.eval(eventIds)
      store = KVStore.mapKVS[IO, NodeId, List[ServerSentEvent]]
      _ <- Stream.eval(nodeNames.traverse(store.put(_, List.empty)))
      service = GossipServer.serverSentEvent(nodeNames)(eventQueues, eventIds, store).service

      server <- BlazeBuilder[IO]
        .withIdleTimeout(Duration.Inf)
        .bindHttp(59234, "localhost")
        .mountService(service, "/")
        .serve
    } yield server
  }

}
