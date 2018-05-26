package chip.model

import cats.Monad
import cats.effect.Effect
import cats.implicits._
import chip.model.Tweet.Content
import doobie.implicits._
import backend.events.Subscriber.{EventType, EventTypeTag}
import chip.events.Replicable
import chip.model.TweetsEvents.{AddTweet, TweetsEvent}
import backend.gossip.GossipDaemon
import org.http4s.EntityDecoder
import io.circe.generic.auto._
import backend.storage.Database
import shapeless.tag
import backend.events.EventTyper
import chip.implicits._
import backend.implicits._
import gossip.Gossipable

/**
  * Tweets DSL
  */
trait Tweets[F[_]] {
  def getTweets(user: User): F[List[Tweet]]
  def getAllTweets: F[List[Tweet]]
  def addTweet(user: User, tweetContent: Content): F[Tweet]
}

object Tweets {
  /* ------ Interpreters ------ */

  /**
    * Interpreter to the [[Database]] and [[GossipDaemon]] DSLs.
    * Replicates the events.
    */
  def replicated[F[_]: Monad: EntityDecoder[?[_], String], E: Gossipable](
      db: Database[F],
      daemon: GossipDaemon[F, E]
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

      daemon.send[TweetsEvent](AddTweet(tweet)).map(_ => tweet)
    }
  }
}

object TweetsEvents {
  sealed trait TweetsEvent
  case class AddTweet(tweet: Tweet) extends TweetsEvent

  object TweetsEvent {
    implicit val namedTweetsEvent: EventTyper[TweetsEvent] =
      new EventTyper[TweetsEvent] {
        val eventType: EventType = tag[EventTypeTag][String]("Tweets")
      }

    implicit val replicableTweetsEvent: Replicable[TweetsEvent] =
      new Replicable[TweetsEvent] {
        def replicate[F[_]: Effect](db: Database[F]): TweetsEvent => F[Unit] = {
          case AddTweet(tweet) => db.insert(sql"""
           INSERT INTO tweets (user_id, content)
           VALUES (${tweet.userId}, ${tweet.content})
          """)
        }
      }
  }
}
