package backend.events

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import cats.effect.{Async, Effect}
import backend.events.Subscriber.{Event, EventIdTag, EventTypeTag, Lsn, PayloadTag}
import fs2.Stream
import fs2.interop.reactivestreams._
import backend.gossip.model.Node.{NodeId, NodeIdTag}
import cats.Functor
import io.circe.{Decoder, Encoder}
import org.http4s
import org.http4s.{DecodeResult, EntityDecoder, Message}
import shapeless.tag.@@
import shapeless.tag
import io.circe.generic.auto._, io.circe.syntax._

import scala.concurrent.ExecutionContext

/**
  * Subscriber DSL
  */
trait Subscriber[F[_]] {
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

      def subscribe(uri: String): Stream[F, Event] =
        Stream.force(implicitly[Async[F]].async[Stream[F, Event]] { cb =>
          (for {
            httpResponse <- Http().singleRequest(HttpRequest(uri = uri))

            akkaStream <- Unmarshal(httpResponse)
              .to[Source[ServerSentEvent, NotUsed]]

            fs2Stream = akkaStream
              .runWith(Sink.asPublisher[ServerSentEvent](fanout = false))
              .toStream[F]
              .flatMap(
                sse =>
                  (for {
                    eventType <- sse.eventType
                    id <- sse.id
                    Array(nodeId, eventId) = id.split("-")
                  } yield
                    Event(Lsn(tag[NodeIdTag][String](nodeId), tag[EventIdTag][Int](eventId.toInt)),
                          tag[EventTypeTag][String](eventType),
                          tag[PayloadTag][String](sse.data))) match {
                    case Some(event) => Stream.emit(event)
                    case None =>
                      Stream.raiseError[Event](
                        new NoSuchElementException(s"Missing event-type or id in $sse"))
                }
              )
          } yield fs2Stream).onComplete(t => cb(t.toEither))
        })
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
}
