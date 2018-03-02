trait Tweets[F[_], User, Tweet] {
  def getTweets(user: User): F[List[Tweet]]
  def addTweet(user: User, tweet: Tweet): F[Option[Tweet]]
}

object Tweets extends TweetsInstances {
  def apply[F[_], User, Tweet](
      implicit T: Tweets[F, User, Tweet]): Tweets[F, User, Tweet] = T
}

trait Tweetable[T] {
  def getText(): String
}

sealed abstract class TweetsInstances {
  implicit def tweets[F[_], User: Userable, Tweet: Tweetable](
      db: Database[F, String]) = new Tweets[F, User, Tweet] {
    import Userable.ops._

    checkDB()

    // Retrieve all tweets posted by the User
    override def getTweets(user: User): F[List[Tweet]] = ???

    override def addTweet(user: User, tweet: Tweet): F[Option[Tweet]] = {
      db.insertAndGet[Tweet](s"""
           |INSERT INTO tweets (user_id, content)
           |VALUES (${user.getId}})
       """.stripMargin)
    }

    private def checkDB() = ???
  }
}
