package chip.model

import cats.Monad
import cats.effect.Effect
import cats.implicits._
import chip.model.TweetsActions.{AddTweet, TweetsAction}
import doobie.implicits._
import events.{EventTypable, Replicable}
import gossip.GossipDaemon
import io.circe.generic.auto._
import org.http4s.EntityDecoder
import storage.Database

trait Tweets[F[_]] {
  def getTweets(user: User): F[List[Tweet]]
  def getAllTweets: F[List[Tweet]]
  def addTweet(user: User, tweetContent: String): F[Tweet]
}

object Tweets extends TweetsInstances {
  def apply[F[_]](implicit T: Tweets[F]): Tweets[F] = T
}

sealed abstract class TweetsInstances {
  implicit def replicated[F[_]: Monad: EntityDecoder[?[_], String]](
      db: Database[F],
      daemon: GossipDaemon[F]
  ): Tweets[F] = new Tweets[F] {

    // Retrieve all tweets posted by the chip.model.User
    def getTweets(user: User): F[List[Tweet]] =
      db.query[Tweet](sql"""
           SELECT *
           FROM tweets
           WHERE user_id = ${user.id}
         """)

    def getAllTweets: F[List[Tweet]] = db.query[Tweet](sql"""
           SELECT *
           FROM tweets
         """)

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
           INSERT INTO tweets (id, user_id, content)
           VALUES (${tweet.id}, ${tweet.userId}, ${tweet.content})
          """)
      }
    }
}