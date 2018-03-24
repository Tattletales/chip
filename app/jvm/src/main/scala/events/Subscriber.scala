package events

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
import events.Subscriber.{Event, EventIdTag, EventTypeTag, Lsn, PayloadTag}
import fs2.Stream
import fs2.interop.reactivestreams._
import gossip.model.Node.{NodeId, NodeIdTag}
import io.circe.{Decoder, Encoder}
import org.http4s
import org.http4s.{DecodeResult, EntityDecoder, Message}
import shapeless.tag.@@
import shapeless.tag

import scala.concurrent.ExecutionContext

trait Subscriber[F[_]] {
  def subscribe(uri: String): Stream[F, Event]
}

object Subscriber extends SubscriberInstances {
  sealed trait EventIdTag
  type EventId = Int @@ EventIdTag

  sealed trait EventTypeTag
  type EventType = String @@ EventTypeTag

  sealed trait PayloadTag
  type Payload = String @@ PayloadTag

  object implicits {
    /* --- NodeId --- */
    implicit def nodeIdEntityDecoder[F[_]](
        implicit D: EntityDecoder[F, String]): EntityDecoder[F, NodeId] =
      new EntityDecoder[F, NodeId] {
        def decode(msg: Message[F], strict: Boolean): DecodeResult[F, NodeId] =
          D.decode(msg, strict).asInstanceOf[DecodeResult[F, NodeId]]
        def consumes: Set[http4s.MediaRange] = D.consumes
      }

    implicit def nodeIdEncoder(implicit E: Encoder[String]): Encoder[NodeId] =
      E.asInstanceOf[Encoder[NodeId]]

    implicit def nodeIdDecoder(implicit E: Decoder[String]): Decoder[NodeId] =
      E.asInstanceOf[Decoder[NodeId]]

    /* --- EventId --- */
    implicit def eventIdEncoder(implicit E: Encoder[Int]): Encoder[EventId] =
      E.asInstanceOf[Encoder[EventId]]

    implicit def eventIdDecoder(implicit E: Decoder[Int]): Decoder[EventId] =
      E.asInstanceOf[Decoder[EventId]]

    /* --- Payload --- */
    implicit def payloadEncoder(implicit E: Encoder[String]): Encoder[Payload] =
      E.asInstanceOf[Encoder[Payload]]

    implicit def payloadDecoder(implicit E: Decoder[String]): Decoder[Payload] =
      E.asInstanceOf[Decoder[Payload]]
  }

  case class Lsn(nodeId: NodeId, eventId: EventId)

  case class Event(lsn: Lsn, eventType: EventType, payload: Payload)

  def apply[F[_]](implicit S: Subscriber[F]): Subscriber[F] = S
}

sealed abstract class SubscriberInstances {
  implicit def serverSentEvent[F[_]: Effect]: Subscriber[F] =
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
}
