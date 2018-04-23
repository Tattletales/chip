package backend.events

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.stream.ActorMaterializer
import akka.stream.alpakka.sse.scaladsl.EventSource
import akka.stream.scaladsl.Sink
import backend.events.Subscriber.Event
import backend.gossip.model.Node.{NodeId, NodeIdTag}
import cats.ApplicativeError
import cats.effect.Effect
import cats.implicits._
import fs2.interop.reactivestreams._
import fs2.{Pipe, Stream}
import shapeless.tag.@@
import shapeless.tag

import scala.concurrent.ExecutionContext

/**
  * Subscriber DSL
  */
trait Subscriber[F[_]] {

  /**
    * Subscribe to the event stream
    */
  def subscribe(uri: String): Stream[F, Event]
}

object Subscriber {
  /* ------ Interpreters ------ */

  /**
    * Interpreter to an `AkkaHTTP` server sent system
    */
  def serverSentEvent[F[_]: Effect]: Subscriber[F] =
    new Subscriber[F] {
      implicit val system: ActorSystem = ActorSystem()
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContext = system.dispatcher

      /**
        * @see [[Subscriber.subscribe]]
        *
        * Failures:
        *   - [[MalformedSSE]] TODO documentation
        */
      def subscribe(uri: String): Stream[F, Event] = {
        val eventSource = EventSource(Uri(uri), (a: HttpRequest) => Http().singleRequest(a), None)

        eventSource
          .runWith(Sink.asPublisher[ServerSentEvent](fanout = false))
          .toStream[F]
          .through(convert)
      }

      /**
        * Convert from [[ServerSentEvent]] to [[Event]]
        *
        * Fails with [[MalformedSSE]] if there's [[ServerSentEvent]] that cannot be decoded.
        * For instance, it doesn't have an event type or the id is not to specs ("nodeId-eventId").
        */
      private def convert[F[_]](
          implicit F: ApplicativeError[F, Throwable]): Pipe[F, ServerSentEvent, Event] = _.evalMap {
        sse =>
          val maybeEvent = for {
            eventType <- sse.eventType
            id <- sse.id
            Array(nodeId, eventId) = id.split("-")
          } yield
            Event(Lsn(tag[NodeIdTag][String](nodeId), tag[EventIdTag][Int](eventId.toInt)),
                  tag[EventTypeTag][String](eventType),
                  tag[PayloadTag][String](sse.data))

          F.fromOption(maybeEvent, MalformedSSE)
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

  case class Event(lsn: Lsn, eventType: EventType, payload: Payload)

  /* ------ Errors ------ */
  sealed trait SubscriberError extends Throwable
  case object MalformedSSE extends SubscriberError
}
