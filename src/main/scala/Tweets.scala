import TweetActions.{AddTweet, TweetAction}
import cats.Monad
import cats.data.OptionT
import doobie.util.composite.Composite
import fs2._

trait Tweets[F[_], User, Tweet] {
  def getTweets(user: User): F[List[Tweet]]
  def addTweet(user: User, tweet: Tweet): F[Option[Tweet]]
}

object Tweets extends TweetsInstances {
  def apply[F[_], User, Tweet](implicit T: Tweets[F, User, Tweet]): Tweets[F, User, Tweet] = T
}

sealed abstract class TweetsInstances {
  implicit def tweets[F[_]: Monad, User: Userable, Tweet: Tweetable: Composite](
    db: Database[Stream[F, ?], String],
    distributor: Distributor[Stream[F, ?], TweetAction]
  ) = new Tweets[Stream[F, ?], User, Tweet] {

    import Tweetable.ops._
    import Userable.ops._

    // Retrieve all tweets posted by the User
    def getTweets(user: User): Stream[F, List[Tweet]] =
      db.query[Tweet](s"""
           |SELECT *
           |FROM tweets
           |WHERE user_id = ${user.getId}
         """.stripMargin)

    def addTweet(user: User, tweet: Tweet): Stream[F, Option[Tweet]] =
      (for {
        tweet <- OptionT(db.insertAndGet[Tweet](s"""
           |INSERT INTO tweets (user_id, content)
           |VALUES (${user.getId}}, ${tweet.getText})
       """.stripMargin, Seq("id", "tweet"): _*))

        _ <- OptionT.liftF(distributor.share(AddTweet(tweet)))
      } yield tweet).value
  }
}

object TweetActions {
  sealed trait TweetAction
  case class AddTweet[Tweet](tweet: Tweet) extends TweetAction
}
