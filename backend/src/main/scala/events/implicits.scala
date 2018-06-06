package backend.events

import cats.Functor
import io.circe.{Decoder, Encoder, Json}
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
  implicit def payloadEncoder(implicit E: Encoder[Json]): Encoder[Payload] = E.contramap(a => a)

  implicit def payloadDecoder(implicit D: Decoder[Json]): Decoder[Payload] =
    D.map(tag[PayloadTag][Json])

  implicit def payloadEntityDecoder[F[_]: Functor](
      implicit D: EntityDecoder[F, Json]): EntityDecoder[F, Payload] =
    D.map(tag[PayloadTag][Json])

  /* --- EventType --- */
  implicit def eventTypeEntityDecoder[F[_]: Functor](
      implicit D: EntityDecoder[F, String]): EntityDecoder[F, EventType] =
    D.map(tag[EventTypeTag][String])

  implicit def eventTypeDecoder(implicit D: Decoder[String]): Decoder[EventType] =
    D.map(tag[EventTypeTag][String])

  implicit def eventTypeEncoder(implicit E: Encoder[String]): Encoder[EventType] =
    E.contramap(a => a)
}
