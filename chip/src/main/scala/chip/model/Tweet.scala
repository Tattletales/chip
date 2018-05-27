package chip.model

import chip.model.Tweet.Content
import chip.model.User.UserId
import io.circe.Encoder
import io.circe.generic.semiauto._
import shapeless.tag.@@
import chip.implicits._
import backend.implicits._

case class Tweet(userId: UserId, content: Content)

object Tweet {
  sealed trait ContentTag
  type Content = String @@ ContentTag
  
  lazy val tweetEncoder: Encoder[Tweet] = deriveEncoder[Tweet]
}
