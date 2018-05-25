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
import scala.concurrent.duration._

import scala.concurrent.ExecutionContext.Implicits.global

sealed trait GossipServer[F[_]] {
  def service: HttpService[F]
}

object GossipServer {
  def default(nodes: List[String])(
      eventQueues: Map[NodeId, Queue[IO, ServerSentEvent]],
      eventIds: Map[NodeId, Ref[IO, Int]],
      logs: KVStore[IO, NodeId, List[ServerSentEvent]]): GossipServer[IO] = new GossipServer[IO] {
    def service: HttpService[IO] = HttpService[IO] {
      case req @ GET -> Root / "events" / nodeId =>
        req.headers.get(headers.`Last-Event-Id`) match {
          case Some(lastEventId) =>
            for {
              log <- logs.get(tag[NodeIdTag][String](nodeId))
              resend = log.get.takeWhile(_.id.get != lastEventId.id)
              _ = println(s"Last ID: ${lastEventId.id}")
              _ = log.get.foreach(m => println(s"Log ($nodeId): $m"))
              _ = resend.foreach(m => println(s"Resending ($nodeId): $m"))
              queue = eventQueues(tag[NodeIdTag][String](nodeId))
              _ <- resend.traverse(queue.enqueue1)
              response <- Ok(queue.dequeue)
            } yield response

          case None =>
            for {
              log <- logs.get(tag[NodeIdTag][String](nodeId))
              queue = eventQueues(tag[NodeIdTag][String](nodeId))
              _ <- log.get.traverse(queue.enqueue1)
              response <- Ok(queue.dequeue)
            } yield response
        }

      case req @ POST -> Root / "gossip" / nodeId =>
        req.decode[UrlForm] { data =>
          val sse = for {
            eventId <- eventIds(tag[NodeIdTag][String](nodeId)).get
            _ <- eventIds(tag[NodeIdTag][String](nodeId)).setSync(eventId + 1)
            eventType = data.values.get("t").map(_.head).get
            event = data.values.get("d").map(_.head).get
          } yield ServerSentEvent(event, Some(eventType), Some(EventId(s"$nodeId-$eventId")))

          def addToLogs(sse: ServerSentEvent): IO[Unit] =
            logs.keys.flatMap(_.toList.traverse(addToLog(_, sse)) *> IO.unit)

          def addToQueues(sse: ServerSentEvent): IO[Unit] =
            eventQueues.values.toList
              .traverse(q => { println(s"\n==== ADDING sse : ${sse}"); q.enqueue1(sse) }) *> IO.unit

          // Run both in parallel
          Ok(sse.flatMap(sse => (addToLogs(sse), addToQueues(sse)).mapN((_, _) => println(sse))))
        }
    }

    private def addToLog(nodeId: NodeId, sse: ServerSentEvent): IO[Unit] =
      for {
        maybeCurrentLog <- logs.get(nodeId)
        currentLog = maybeCurrentLog.get
        _ <- logs.put(nodeId, sse :: currentLog)
      } yield ()

    private def heartbeat =
      Stream.every(5 seconds).filter(_ == true).map(_ => ServerSentEvent(""))
  }
}
