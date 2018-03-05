import Database.Query
import TweetActions.{AddTweet, TweetsAction}
import cats.Monad
import cats.data.OptionT
import doobie.util.composite.Composite
import fs2._

trait Tweets[F[_]] {
  def getTweets(user: User): F[List[Tweet]]
  def addTweet(user: User, tweet: Tweet): F[Option[Tweet]]
}

object Tweets extends TweetsInstances {
  def apply[F[_]](implicit T: Tweets[F]): Tweets[F] = T
}

sealed abstract class TweetsInstances {
  implicit def replicated[F[_]: Monad](
    db: Database[Stream[F, ?]],
    distributor: Distributor[Stream[F, ?], TweetsAction]
  ): Tweets[Stream[F, ?]] = new Tweets[Stream[F, ?]] {

    // Retrieve all tweets posted by the User
    def getTweets(user: User): Stream[F, List[Tweet]] =
      db.query[Tweet](Query(s"""
           |SELECT *
           |FROM tweets
           |WHERE user_id = ${user.id}
         """.stripMargin))

    def addTweet(user: User, tweet: Tweet): Stream[F, Option[Tweet]] =
      (for {
        tweet <- OptionT(db.insertAndGet[Tweet](Query(s"""
           |INSERT INTO tweets (user_id, content)
           |VALUES (${user.id}}, ${tweet.content})
          """.stripMargin), Seq("id", "tweet"): _*))

        _ <- OptionT.liftF(distributor.share(AddTweet(tweet)))
      } yield tweet).value
  }
}

object TweetActions {
  sealed trait TweetsAction
  case class AddTweet(tweet: Tweet) extends TweetsAction
}
