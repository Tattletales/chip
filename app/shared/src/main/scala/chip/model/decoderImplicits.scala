package chip.model

import chip.model.Tweet.{Content, ContentTag, TweetId, TweetIdTag}
import chip.model.User.{UserId, UserIdTag, Username, UsernameTag}
import io.circe.Decoder
import shapeless.tag

object decoderImplicits {
  implicit def contentDecoder(implicit D: Decoder[String]): Decoder[Content] =
    D.map(tag[ContentTag][String](_))

  implicit def tweetIdDecoder(implicit D: Decoder[String]): Decoder[TweetId] =
    D.map(tag[TweetIdTag][String](_))

  implicit def userIdDecoder(implicit D: Decoder[String]): Decoder[UserId] =
    D.map(tag[UserIdTag][String](_))

  implicit def usernameDecoder(implicit D: Decoder[String]): Decoder[Username] =
    D.map(tag[UsernameTag][String](_))
}
