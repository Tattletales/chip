package chip
package model

import cats.Monad
import cats.effect.Effect
import cats.implicits._
import Tweet.Content
import doobie.implicits._
import events.Replicable
import TweetsEvents.{AddTweet, TweetsEvent}
import backend.gossip.GossipDaemon
import org.http4s.EntityDecoder
import io.circe.generic.semiauto._
import io.circe.generic.auto._
import backend.storage.Database
import shapeless.tag
import backend.events._
import implicits._
import backend.implicits._
import backend.gossip.Gossipable
import io.circe.Encoder

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
      daemon: GossipDaemon[F, TweetsEvent, E]
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

      daemon.send(AddTweet(tweet)).map(_ => tweet)
    }
  }
}

object TweetsEvents {
  sealed trait TweetsEvent
  final case class AddTweet(tweet: Tweet) extends TweetsEvent

  object TweetsEvent {
    implicit val namedTweetsEvent: EventTyper[TweetsEvent] =
      new EventTyper[TweetsEvent] {
        val eventType: EventType = tag[EventTypeTag][String]("Tweets")
      }

    implicit val eventTypableTweetsEvent: EventTypable[TweetsEvent] =
      new EventTypable[TweetsEvent] {
        def eventType(e: TweetsEvent): EventType = tag[EventTypeTag][String]("Tweets")
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

    implicit val tweetsEventEncoder: Encoder[TweetsEvent] = deriveEncoder[TweetsEvent]
  }
}
