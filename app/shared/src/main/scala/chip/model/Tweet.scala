package chip.model

import chip.model.Tweet.{Content, TweetId}
import chip.model.User.UserId
import shapeless.tag.@@

case class Tweet(id: TweetId, userId: UserId, content: Content)

object Tweet {
  sealed trait TweetIdTag
  type TweetId = String @@ TweetIdTag

  sealed trait ContentTag
  type Content = String @@ ContentTag
}
