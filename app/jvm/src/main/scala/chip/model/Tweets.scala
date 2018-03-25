package chip.model

import cats.Monad
import cats.effect.Effect
import cats.implicits._
import chip.model.Tweet.Content
import doobie.implicits._
import backend.events.Subscriber.{EventType, EventTypeTag}
import chip.events.Replicable
import chip.model.TweetsActions.{AddTweet, TweetsAction}
import backend.gossip.GossipDaemon
import org.http4s.EntityDecoder
import io.circe.generic.auto._
import backend.storage.Database
import shapeless.tag
import backend.events.EventTyper
import chip.implicits._
import backend.implicits._

trait Tweets[F[_]] {
  def getTweets(user: User): F[List[Tweet]]
  def getAllTweets: F[List[Tweet]]
  def addTweet(user: User, tweetContent: Content): F[Tweet]
}

object Tweets extends TweetsInstances {
  def apply[F[_]](implicit T: Tweets[F]): Tweets[F] = T
}

sealed abstract class TweetsInstances {
  implicit def replicated[F[_]: Monad: EntityDecoder[?[_], String]](
      db: Database[F],
      daemon: GossipDaemon[F]
  ): Tweets[F] = new Tweets[F] {

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

    def addTweet(user: User, tweetContent: Content): F[Tweet] = {
      val tweet = Tweet(user.id, tweetContent)

      daemon.send[TweetsAction](AddTweet(tweet)).map(_ => tweet)
    }
  }
}

object TweetsActions {
  sealed trait TweetsAction
  case class AddTweet(tweet: Tweet) extends TweetsAction

  object TweetsAction {
    implicit val namedTweetsAction: EventTyper[TweetsAction] =
      new EventTyper[TweetsAction] {
        val eventType: EventType = tag[EventTypeTag][String]("Tweets")
      }

    implicit val replicableTweetsAction: Replicable[TweetsAction] =
      new Replicable[TweetsAction] {
        def replicate[F[_]: Effect](db: Database[F]): TweetsAction => F[Unit] = {
          case AddTweet(tweet) => db.insert(sql"""
           INSERT INTO tweets (user_id, content)
           VALUES (${tweet.userId}, ${tweet.content})
          """)
        }
      }
  }
}
