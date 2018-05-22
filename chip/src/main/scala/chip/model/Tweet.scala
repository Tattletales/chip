package chip.model

import chip.model.Tweet.Content
import chip.model.User.UserId
import shapeless.tag.@@

case class Tweet(userId: UserId, content: Content)

object Tweet {
  sealed trait ContentTag
  type Content = String @@ ContentTag
}
