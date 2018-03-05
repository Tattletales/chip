import Tweet._
import User.UserId

case class Tweet(id: TweetId, userId: UserId, content: TweetContent)

object Tweet {
  case class TweetId(id: Int) extends AnyVal
  case class TweetContent(content: String) extends AnyVal
}
