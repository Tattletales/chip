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
import fs2.async.Ref
import fs2.async.mutable.Queue
import shapeless.tag
import fs2.Stream
import org.http4s.ServerSentEvent.EventId

import scala.collection.parallel.immutable

sealed trait GossipServer[F[_]] {
  def service: HttpService[F]
}

object GossipServer {
  def default(nodes: List[String])(
      eventQueues: Map[NodeId, Queue[IO, ServerSentEvent]],
      eventIds: Map[NodeId, Ref[IO, Int]],
      logs: KVStore[IO, NodeId, List[ServerSentEvent]]): GossipServer[IO] = new GossipServer[IO] {
    def service: HttpService[IO] = HttpService[IO] {
      case GET -> Root / "events" / nodeId =>
        Ok(eventQueues(tag[NodeIdTag][String](nodeId)).dequeue)

      //case GET -> Root / "log" / nodeId =>
      //  Ok(logs.get(tag[NodeIdTag][String](nodeId)).map(_.get))

      case req @ POST -> Root / "gossip" / nodeId =>
        req.decode[UrlForm] { data =>
          val sse = for {
            eventId <- eventIds(tag[NodeIdTag][String](nodeId)).get
            _ <- eventIds(tag[NodeIdTag][String](nodeId)).modify(_ + 1)
            eventType = data.values.get("t").map(_.head).get
            event = data.values.get("d").map(_.head).get
          } yield ServerSentEvent(event, Some(eventType), Some(EventId(eventId.toString)))

          def addToLogs(sse: ServerSentEvent): IO[Unit] =
            logs.keys.flatMap(_.toList.traverse(addToLog(_, sse)) *> IO.unit)

          def addToQueues(sse: ServerSentEvent): IO[Unit] =
            eventQueues.values.toList
              .traverse(_.enqueue1(sse)) *> IO.unit

          // Run both in parallel
          Ok(sse.flatMap(sse => (addToLogs(sse), addToQueues(sse)).mapN((_, _) => ())))
        }
    }

    private def addToLog(nodeId: NodeId, sse: ServerSentEvent): IO[Unit] =
      for {
        maybeCurrentLog <- logs.get(nodeId)
        currentLog = maybeCurrentLog.get
        _ <- logs.put(nodeId, sse :: currentLog)
      } yield ()
  }
}
