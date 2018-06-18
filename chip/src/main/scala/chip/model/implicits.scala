package chip
package model

import cats.Functor
import Tweet._
import User._
import doobie.util.meta.Meta
import io.circe.{Decoder, Encoder}
import org.http4s.EntityDecoder
import shapeless.tag

trait implicits {
  /* --- Content --- */
  implicit def contentMeta(implicit M: Meta[String]): Meta[Content] =
    M.xmap(tag[ContentTag][String], a => a)

  implicit def contentEncoder(implicit E: Encoder[String]): Encoder[Content] = E.contramap(a => a)

  implicit def contentEntityDecoder[F[_]: Functor](
      implicit D: EntityDecoder[F, String]): EntityDecoder[F, Content] =
    D.map(tag[ContentTag][String])

  implicit def contentDecoder(implicit D: Decoder[String]): Decoder[Content] =
    D.map(tag[ContentTag][String](_))

  /* --- Username --- */
  implicit def usernameMeta(implicit M: Meta[String]): Meta[Username] =
    M.xmap(tag[UsernameTag][String], a => a)

  implicit def usernameEncoder(implicit E: Encoder[String]): Encoder[Username] =
    E.contramap(a => a)

  implicit def usernameDecoder(implicit D: Decoder[String]): Decoder[Username] =
    D.map(tag[UsernameTag][String](_))
}
