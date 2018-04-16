package backend.events

import backend.events.Subscriber.{
  EventId,
  EventIdTag,
  EventType,
  EventTypeTag,
  Payload,
  PayloadTag
}
import backend.gossip.model.Node.{NodeId, NodeIdTag}
import cats.Functor
import io.circe.{Decoder, Encoder}
import org.http4s.EntityDecoder
import shapeless.tag

trait implicits {

  /* --- EventId --- */
  implicit def eventIdEncoder(implicit E: Encoder[Int]): Encoder[EventId] = E.contramap(a => a)

  implicit def eventIdDecoder(implicit D: Decoder[Int]): Decoder[EventId] =
    D.map(tag[EventIdTag][Int])

  implicit def eventIdEntityDecoder[F[_]: Functor](
      implicit D: EntityDecoder[F, Int]): EntityDecoder[F, EventId] =
    D.map(tag[EventIdTag][Int])

  /* --- Payload --- */
  implicit def payloadEncoder(implicit E: Encoder[String]): Encoder[Payload] =
    E.asInstanceOf[Encoder[Payload]]

  implicit def payloadDecoder(implicit E: Decoder[String]): Decoder[Payload] =
    E.asInstanceOf[Decoder[Payload]]

  implicit def payloadEntityDecoder[F[_]: Functor](
      implicit D: EntityDecoder[F, String]): EntityDecoder[F, Payload] =
    D.map(tag[PayloadTag][String])

  /* --- EventType --- */
  implicit def eventTypeEntityDecoder[F[_]: Functor](
      implicit D: EntityDecoder[F, String]): EntityDecoder[F, EventType] =
    D.map(tag[EventTypeTag][String])
}
