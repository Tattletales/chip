package backend.events

import akka.actor.ActorSystem
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.ActorMaterializer
import akka.stream.alpakka.sse.scaladsl.EventSource
import akka.stream.scaladsl.Sink
import backend.errors.MalformedSSE
import backend.gossip.Node.{NodeId, NodeIdTag}
import backend.implicits._
import cats.ApplicativeError
import cats.effect.Effect
import cats.implicits._
import io.circe.generic.auto._
import io.circe.Json
import fs2.interop.reactivestreams._
import fs2.{Pipe, Stream}
import gossip.Gossipable
import network.WebSocketClient
import shapeless.tag.@@
import shapeless.tag

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

/**
  * Subscriber DSL
  *
  * E is the type of the events that are delivered.
  */
trait Subscriber[F[_], E] {

  /**
    * Subscribe to the event stream
    */
  def subscribe(uri: String): Stream[F, E]
}

object Subscriber {
  /* ------ Interpreters ------ */

  /**
    * Interpreter to an `AkkaHTTP` server sent system
    */
  def serverSentEvent[F[_]: Effect]: Subscriber[F, SSEvent] =
    new Subscriber[F, SSEvent] {
      implicit val system: ActorSystem = ActorSystem()
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContext = system.dispatcher

      /**
        * @see [[Subscriber.subscribe]]
        *
        * Failures:
        *   - [[MalformedSSE]] TODO documentation
        */
      def subscribe(uri: String): Stream[F, SSEvent] = {
        val eventSource = EventSource(
          Uri(uri),
          (a: HttpRequest) =>
            Http().singleRequest(a,
                                 settings =
                                   ConnectionPoolSettings(system).withIdleTimeout(Duration.Inf)),
          None)

        eventSource
          .runWith(Sink.asPublisher[ServerSentEvent](fanout = false))
          .toStream[F]
          .through(convert)

      }

      /**
        * Convert from [[ServerSentEvent]] to [[WSEvent]]
        *
        * Fails with [[MalformedSSE]] if there's [[ServerSentEvent]] that cannot be decoded.
        * For instance, it doesn't have an event type or the id is not to specs ("nodeId-eventId").
        */
      private def convert[F[_]](
          implicit F: ApplicativeError[F, Throwable]): Pipe[F, ServerSentEvent, SSEvent] =
        _.evalMap { sse =>
          val maybeEvent = for {
            eventType <- sse.eventType
            id <- sse.id
            Array(nodeId, eventId) = id.split("-")
          } yield
            SSEvent(Lsn(tag[NodeIdTag][String](nodeId), tag[EventIdTag][Int](eventId.toInt)),
                    tag[EventTypeTag][String](eventType),
                    tag[PayloadTag][String](sse.data))

          F.fromOption(maybeEvent, MalformedSSE(sse))
        }
    }

  /* ------ Types ------ */
  sealed trait EventIdTag
  type EventId = Int @@ EventIdTag

  sealed trait EventTypeTag
  type EventType = String @@ EventTypeTag

  sealed trait PayloadTag
  type Payload = String @@ PayloadTag

  case class Lsn(nodeId: NodeId, eventId: EventId)

  /**
    * Event sent by the WebSocket protocol.
    */
  case class WSEvent(lsn: Lsn, payload: Payload)
  object WSEvent {
    implicit def gossipableWSEvent: Gossipable[WSEvent] = new Gossipable[WSEvent] {
      def lsn(e: WSEvent): Lsn = e.lsn
      def payload(e: WSEvent): Payload = e.payload
    }
  }

  /**
    * Event sent by the ServerSentEvent protocol
    */
  case class SSEvent(lsn: Lsn, eventType: EventType, payload: Payload)
  object SSEvent {
    implicit def gossipableSSEvent: Gossipable[SSEvent] = new Gossipable[SSEvent] {
      def lsn(e: SSEvent): Lsn = e.lsn
      def payload(e: SSEvent): Payload = e.payload
    }
    
    implicit def eventTypable: EventTypable[SSEvent] = new EventTypable[SSEvent] {
      def eventType(e: SSEvent): EventType = e.eventType
    } 
  }
}
