package chip
package model

import chip.model.Tweet.Content
import chip.model.User.UserId
import io.circe.Encoder
import io.circe.generic.semiauto._
import shapeless.tag.@@
import implicits._
import backend.implicits._

final case class Tweet(userId: UserId, content: Content)

object Tweet {
  sealed trait ContentTag
  type Content = String @@ ContentTag

  lazy val tweetEncoder: Encoder[Tweet] = deriveEncoder[Tweet]
}
