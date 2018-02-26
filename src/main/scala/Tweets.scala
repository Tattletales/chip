object Tweets {
  trait Alg[F[_], User, Tweet] {
    def getTweets(user: User): F[List[Tweet]]
    def addTweet(user: User, tweet: Tweet): F[Unit]
  }
}



