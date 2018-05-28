package chip.events

import backend.events.EventTypable
import backend.gossip.Gossipable
import backend.storage.Database
import backend.implicits._
import cats.effect.Effect
import chip.events.ReplicateEvents.{baseCase, inductionStep}
import chip.model.TweetsEvents.TweetsEvent
import chip.model.UsersEvents.UsersEvent
import chip.implicits._
import io.circe.generic.auto._
import fs2._
import shapeless.{::, HNil}

/**
  * Replicate the events to the database.
  */
object Replicator {
  def apply[F[_]: Effect, E: EventTypable: Gossipable](db: Database[F],
                                                       events: Stream[F, E]): Stream[F, Unit] = {
    val handler = implicitly[ReplicateEvents[TweetsEvent :: UsersEvent :: HNil, E]]

    events.evalMap(handler.replicate(db)(_))
  }
}
