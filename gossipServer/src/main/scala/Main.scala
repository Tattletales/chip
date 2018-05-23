package gossipServer

import backend.gossip.Node.{NodeId, NodeIdTag}
import backend.storage.KVStore
import cats.effect.IO
import cats.implicits._
import fs2.StreamApp.ExitCode
import fs2.{Stream, StreamApp, async}
import org.http4s.ServerSentEvent
import org.http4s.server.blaze.BlazeBuilder
import shapeless.tag

import scala.concurrent.ExecutionContext.Implicits.global

class Main extends StreamApp[IO] {
  override def stream(args: List[String], requestShutdown: IO[Unit]): Stream[IO, ExitCode] = {
    val nodes = args(0).toInt
    val nodeNames = args.slice(1, nodes).map(tag[NodeIdTag][String])

    val eventQueues = nodeNames
      .traverse(_ => async.unboundedQueue[IO, ServerSentEvent])
      .map(nodeNames.zip(_).toMap)

    for {
      eventQueues <- Stream.eval(eventQueues)
      store = KVStore.mapKVS[IO, NodeId, List[ServerSentEvent]]
      service = GossipServer.default(nodeNames)(eventQueues, store).service

      server <- BlazeBuilder[IO]
        .bindHttp(8080, "localhost")
        .mountService(service, "/")
        .serve
    } yield server

  }
}
