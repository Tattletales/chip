package gossipServer

import backend.events.Event.{EventIdTag, Lsn, PayloadTag}
import backend.events.WSEvent
import backend.gossip.Node.{NodeId, NodeIdTag}
import backend.implicits._
import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import backend.storage.KVStore
import fs2.{Pipe, Sink}
import fs2.async.Ref
import fs2.async.mutable.Queue
import shapeless.tag
import io.circe.syntax._
import io.circe.generic.auto._
import org.http4s.ServerSentEvent.EventId
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebsocketBits.{Text, WebSocketFrame}

sealed trait GossipServer[F[_], O] {
  def service: HttpService[F]
}

object GossipServer {
  def serverSentEvent(nodes: List[String])(
      eventQueues: Map[NodeId, Queue[IO, ServerSentEvent]],
      eventIds: Map[NodeId, Ref[IO, Int]],
      logs: KVStore[IO, NodeId, List[ServerSentEvent]]): GossipServer[IO, ServerSentEvent] =
    new GossipServer[IO, ServerSentEvent] {
      def service: HttpService[IO] = HttpService[IO] {
        case req @ GET -> Root / "events" / nodeId =>
          req.headers.get(headers.`Last-Event-Id`) match {
            case Some(lastEventId) =>
              for {
                log <- logs.get(tag[NodeIdTag][String](nodeId))
                resend = log.get.takeWhile(_.id.get != lastEventId.id)
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
              logs.keys.flatMap(_.toList.traverse(addToLog(_, sse)).void)

            def addToQueues(sse: ServerSentEvent): IO[Unit] =
              eventQueues.values.toList.traverse(_.enqueue1(sse)).void

            // Run both in parallel
            Ok(sse.flatMap(sse => (addToLogs(sse), addToQueues(sse)).mapN((_, _) => IO.unit)))
          }
      }

      private def addToLog(nodeId: NodeId, sse: ServerSentEvent): IO[Unit] =
        for {
          maybeCurrentLog <- logs.get(nodeId)
          currentLog = maybeCurrentLog.get
          _ <- logs.put(nodeId, sse :: currentLog)
        } yield ()
    }

  def webSocket[F[_]](nodes: List[String])(
      eventQueues: Map[NodeId, Queue[F, WSEvent]],
      eventIds: Map[NodeId, Ref[F, Int]],
      logs: KVStore[F, NodeId, List[WSEvent]])(implicit F: Effect[F]): GossipServer[F, WSEvent] =
    new GossipServer[F, WSEvent] {
      def service: HttpService[F] = HttpService[F] {
        case GET -> Root / "events" / nodeId =>
          val outgoing =
            eventQueues(tag[NodeIdTag][String](nodeId)).dequeue.through(handleOutgoing)
          val incoming = handleIncoming(tag[NodeIdTag][String](nodeId))
          WebSocketBuilder[F].build(outgoing, incoming)
      }

      /**
        * Queue and log the incoming events sent by nodeId to be sent to all the nodes.
        */
      private def handleIncoming(nodeId: NodeId): Sink[F, WebSocketFrame] = {
        def addToLogs(event: WSEvent): F[Unit] =
          logs.keys.flatMap(_.toList.traverse(addToLog(_, event)).void)

        def addToQueues(event: WSEvent): F[Unit] =
          eventQueues.values.toList.traverse(_.enqueue1(event)).void

        _.evalMap {
          case Text(payload, _) =>
            for {
              eventId <- eventIds(tag[NodeIdTag][String](nodeId)).get
              _ <- eventIds(tag[NodeIdTag][String](nodeId)).setSync(eventId + 1)

              event = WSEvent(Lsn(nodeId, tag[EventIdTag][Int](eventId)),
                              tag[PayloadTag][String](payload))

              _ <- addToLogs(event)
              _ <- addToQueues(event)
            } yield ()
        }
      }

      private val handleOutgoing: Pipe[F, WSEvent, WebSocketFrame] = _.map { event =>
        Text(event.asJson.noSpaces)
      }

      /**
        * Add the event to the log of nodeId.
        */
      private def addToLog(nodeId: NodeId, event: WSEvent): F[Unit] =
        for {
          maybeCurrentLog <- logs.get(nodeId)
          currentLog = maybeCurrentLog.get
          _ <- logs.put(nodeId, event :: currentLog)
        } yield ()
    }

}
