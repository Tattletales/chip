package chip.model

import cats.Functor
import chip.model.Tweet.{Content, ContentTag}
import chip.model.User.{Username, UsernameTag}
import doobie.util.meta.Meta
import io.circe.Encoder
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

  /* --- Username --- */
  implicit def usernameMeta(implicit M: Meta[String]): Meta[Username] =
    M.xmap(tag[UsernameTag][String], a => a)

  implicit def usernameEncoder(implicit E: Encoder[String]): Encoder[Username] =
    E.contramap(a => a)
}
