package gossipServer

import backend.gossip.Node.{NodeId, NodeIdTag}
import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import backend.storage.KVStore
import cats.UnorderedTraverse
import fs2.async
import fs2.async.mutable.Queue
import shapeless.tag

import scala.collection.parallel.immutable

sealed trait GossipServer[F[_]] {
  def service: HttpService[F]
}

object GossipServer {
  def default(nodes: List[String])(
      eventQueues: Map[NodeId, Queue[IO, ServerSentEvent]],
      logs: KVStore[IO, NodeId, List[ServerSentEvent]]): GossipServer[IO] = new GossipServer[IO] {
    def service: HttpService[IO] = HttpService[IO] {
      case GET -> Root / "events" / nodeId =>
        Ok(eventQueues(tag[NodeIdTag][String](nodeId)).dequeue)

      //case GET -> Root / "log" / nodeId =>
      //  Ok(logs.get(tag[NodeIdTag][String](nodeId)).map(_.get))

      case PUT -> Root / "gossip" / eventType / event =>
        val sse = ServerSentEvent(event, Some(eventType))

        val addToLogs = logs.keys.flatMap(_.toList.traverse(addToLog(_, sse)) *> IO.unit)

        val addToQueues = eventQueues.values.toList
          .traverse(_.enqueue1(ServerSentEvent(event, Some(eventType)))) *> IO.unit

        // Run both in parallel
        Ok((addToLogs, addToQueues).mapN((_, _) => ()))
    }

    private def addToLog(nodeId: NodeId, sse: ServerSentEvent): IO[Unit] =
      for {
        maybeCurrentLog <- logs.get(nodeId)
        currentLog = maybeCurrentLog.get
        _ <- logs.put(nodeId, sse :: currentLog)
      } yield ()
  }
}
