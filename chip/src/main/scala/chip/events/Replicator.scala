package chip.events

import backend.events.EventTypable
import cats.effect.Effect
import chip.model.TweetsEvents.TweetsEvent
import chip.model.UsersEvents.UsersEvent
import chip.events.ReplicateEvents.{baseCase, inductionStep}
import backend.events.Subscriber._
import io.circe.generic.auto._
import fs2._
import fs2.async.Ref
import shapeless.{::, HNil}
import backend.storage.Database
import utils.StreamUtils.log
import chip.implicits._
import backend.implicits._
import gossip.Gossipable

/**
  * Replicate the events to the database.
  */
object Replicator {
  def apply[F[_]: Effect, E: EventTypable: Gossipable](db: Database[F], events: Stream[F, E]): Stream[F, Unit] = {
    val handler = implicitly[ReplicateEvents[TweetsEvent :: UsersEvent :: HNil, E]]

    events
      .through(log("Replicator"))
      .evalMap(handler.replicate(db)(_))
  }
}
