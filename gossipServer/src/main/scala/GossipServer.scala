package gossipServer

import backend.events.WSEvent
import backend.gossip.Node.{NodeId, NodeIdTag}
import backend.implicits._
import backend.events._
import cats.effect._
import cats.implicits._
import org.http4s._
import org.http4s.dsl.io._
import backend.storage.KVStore
import fs2.{Pipe, Scheduler, Sink, Stream}
import fs2.async.Ref
import fs2.async.mutable.Queue
import io.circe.Json
import shapeless.tag
import io.circe.syntax._
import io.circe.jawn._
import io.circe.generic.auto._
import org.http4s.ServerSentEvent.EventId
import org.http4s.server.websocket.WebSocketBuilder
import org.http4s.websocket.WebsocketBits.{Text, WebSocketFrame}

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._
import scala.util.Random

/**
  * GossipServer DSL
  */
sealed trait GossipServer[F[_], O] {
  def service: HttpService[F]
}

object GossipServer {

  /**
    * Server using Server Sent Events
    */
  def serverSentEvent(nodes: List[String])(eventQueues: Map[NodeId, Queue[IO, ServerSentEvent]],
                                           eventIds: Map[NodeId, Ref[IO, Int]],
                                           logs: KVStore[IO, NodeId, List[ServerSentEvent]])(
      implicit ec: ExecutionContext): GossipServer[IO, ServerSentEvent] =
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

  /**
    * Server using WebSockets
    */
  def webSocket[F[_]](eventQueues: Map[NodeId, Queue[F, WSEvent]],
                      eventIds: Map[NodeId, Ref[F, Int]],
                      logs: KVStore[F, NodeId, List[WSEvent]])(
      scheduler: Scheduler)(implicit F: Effect[F], ec: ExecutionContext): GossipServer[F, WSEvent] =
    new GossipServer[F, WSEvent] {
      def service: HttpService[F] = HttpService[F] {
        case GET -> Root / "gossip" / nodeId =>
          val outgoing =
            eventQueues(tag[NodeIdTag][String](nodeId)).dequeue.through(handleOutgoing)
          val incoming = handleIncoming(tag[NodeIdTag][String](nodeId))
          WebSocketBuilder[F].build(outgoing, incoming)
      }

      /**
        * Queue and log the incoming events sent by nodeId to be sent to all the nodes.
        */
      private def handleIncoming(nodeId: NodeId): Sink[F, WebSocketFrame] =
        _.flatMap {
          case Text(payload, _) =>
            for {
              eventId <- Stream.eval(eventIds(tag[NodeIdTag][String](nodeId)).get)
              _ <- Stream.eval(eventIds(tag[NodeIdTag][String](nodeId)).setSync(eventId + 1))

              payload <- Stream.eval(F.fromEither(parse(payload)).map(tag[PayloadTag][Json]))
              event = WSEvent(Lsn(nodeId, tag[EventIdTag][Int](eventId)), payload)

              _ <- Stream(eventIds.keys)
                .covary[F]
                .flatMap(_.toList.traverse(addToLogAndQueueWithDelay(_, event)))
                .void
            } yield ()

        }

      /**
        * Decode the event and store it in a `WebSocketFrame`.
        */
      private val handleOutgoing: Pipe[F, WSEvent, WebSocketFrame] = _.map { event =>
        Text(event.asJson.noSpaces)
      }

      /**
        * Add `event` to the log and message queue of `nodeId` after a random delay between
        * 0 and 500ms.
        */
      private def addToLogAndQueueWithDelay(nodeId: NodeId, event: WSEvent): Stream[F, Unit] =
        for {
          _ <- scheduler.sleep_({
            val t = Random.nextInt(500)
            println(s"Delay by ${t}ms for $nodeId")
            FiniteDuration(t, MILLISECONDS)
          }) ++ Stream.eval(addToLog(nodeId, event) *> addToQueue(nodeId, event))
        } yield ()

      /**
        * Add the event to the log of nodeId.
        */
      private def addToLog(nodeId: NodeId, event: WSEvent): F[Unit] =
        for {
          maybeCurrentLog <- logs.get(nodeId)
          currentLog = maybeCurrentLog.get
          _ <- logs.put(nodeId, event :: currentLog)
        } yield ()

      /**
        * Add `event` the message queue of `nodeId`.
        */
      private def addToQueue(nodeId: NodeId, event: WSEvent): F[Unit] =
        eventQueues(nodeId).enqueue1(event)
    }

}
