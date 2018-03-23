package chip.model

import cats.Functor
import chip.model.Tweet.{Content, ContentTag, TweetId, TweetIdTag}
import chip.model.User.{UserId, UserIdTag, Username, UsernameTag}
import doobie.util.meta.Meta
import io.circe.Encoder
import org.http4s.EntityDecoder
import shapeless.tag

object implicits {
  /* --- TweetId --- */
  implicit def tweetIdMeta(implicit M: Meta[String]): Meta[TweetId] =
    M.xmap(tag[TweetIdTag][String](_), a => a)

  implicit def tweetIdEncoder(implicit E: Encoder[String]): Encoder[TweetId] = E.contramap(a => a)

  /* --- Content --- */
  implicit def contentMeta(implicit M: Meta[String]): Meta[Content] =
    M.xmap(tag[ContentTag][String](_), a => a)

  implicit def contentEncoder(implicit E: Encoder[String]): Encoder[Content] = E.contramap(a => a)

  implicit def contentEntityDecoder[F[_]: Functor](
      implicit D: EntityDecoder[F, String]): EntityDecoder[F, Content] =
    D.map(tag[ContentTag][String](_))

  /* --- UserId --- */
  implicit def userIdMeta(implicit M: Meta[String]): Meta[UserId] =
    M.xmap(tag[UserIdTag][String](_), a => a)

  implicit def userIdEncoder(implicit E: Encoder[String]): Encoder[UserId] = E.contramap(a => a)

  /* --- Username --- */
  implicit def usernameMeta(implicit M: Meta[String]): Meta[Username] =
    M.xmap(tag[UsernameTag][String](_), a => a)

  implicit def usernameEncoder(implicit E: Encoder[String]): Encoder[Username] =
    E.contramap(a => a)
}
