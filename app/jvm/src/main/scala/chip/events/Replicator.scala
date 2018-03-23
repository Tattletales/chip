package chip.events

import cats.effect.Effect
import chip.model.TweetsActions.TweetsAction
import chip.model.UsersActions.UsersAction
import events.ReplicateEvents
import events.ReplicateEvents.{baseCase, inductionStep}
import events.Subscriber._
import io.circe.generic.auto._
import fs2._
import fs2.async.Ref
import shapeless.{::, HNil}
import storage.Database
import utils.StreamUtils.log
import chip.model.decoderImplicits._

object Replicator {
  def apply[F[_]: Effect](vClock: Ref[F, Map[NodeId, EventId]],
                          db: Database[F],
                          events: Stream[F, Event]): Stream[F, Unit] = {
    val handler = implicitly[ReplicateEvents[TweetsAction :: UsersAction :: HNil]]

    events
      .through(log("Replicator"))
      .evalMap(handler.replicate(vClock, db)(_))
  }
}
