import TweetsActions.{AddTweet, TweetsAction}
import cats.effect.Effect
import cats.implicits._
import cats.{Monad, ~>}
import doobie.implicits._
import io.circe.generic.auto._
import org.http4s.EntityDecoder

trait Tweets[F[_]] {
  def getTweets(user: User): F[List[Tweet]]
  def addTweet(user: User, tweetContent: String): F[Tweet]
}

object Tweets extends TweetsInstances {
  def apply[F[_]](implicit T: Tweets[F]): Tweets[F] = T
}

sealed abstract class TweetsInstances {
  implicit def replicated[F[_]: Monad, G[_]: EntityDecoder[?[_], String]](
      db: Database[G],
      daemon: GossipDaemon[F]
  )(implicit gToF: G ~> F): Tweets[F] = new Tweets[F] {

    // Retrieve all tweets posted by the User
    def getTweets(user: User): F[List[Tweet]] =
      gToF(db.query[Tweet](sql"""
           SELECT *
           FROM tweets
           WHERE user_id = ${user.id}
         """))

    def addTweet(user: User, tweetContent: String): F[Tweet] =
      for {
        tweetId <- daemon.getUniqueId
        tweet = Tweet(tweetId, user.id, tweetContent)
        _ <- daemon.send[TweetsAction](AddTweet(tweet))
      } yield tweet
  }
}

object TweetsActions {
  sealed trait TweetsAction
  case class AddTweet(tweet: Tweet) extends TweetsAction

  implicit val namedTweetsAction: EventTypable[TweetsAction] =
    new EventTypable[TweetsAction] {
      val eventType: String = "Tweets"
    }

  implicit val replicableTweetsAction: Replicable[TweetsAction] =
    new Replicable[TweetsAction] {
      def replicate[F[_]: Effect](db: Database[F]): TweetsAction => F[Unit] = {
        case AddTweet(tweet) => db.insert(sql"""
           INSERT INTO tweets (tweetId, userId, content)
           VALUES (${tweet.id}}, ${tweet.userId}, ${tweet.content})
          """)
      }
    }
}