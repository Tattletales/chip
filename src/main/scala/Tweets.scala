trait Tweets[F[_], User, Tweet] {
  def getTweets(user: User): F[List[Tweet]]
  def addTweet(user: User, tweet: Tweet): F[Unit]
}

object Tweets extends TweetsInstances {
  def apply[F[_], User, Tweet](
      implicit T: Tweets[F, User, Tweet]): Tweets[F, User, Tweet] = T
}

sealed abstract class TweetsInstances {}
