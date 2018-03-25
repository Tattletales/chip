package chip.events

import cats.effect.Effect
import chip.model.TweetsActions.TweetsAction
import chip.model.UsersActions.UsersAction
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

object Replicator {
  def apply[F[_]: Effect](db: Database[F], events: Stream[F, Event]): Stream[F, Unit] = {
    val handler = implicitly[ReplicateEvents[TweetsAction :: UsersAction :: HNil]]

    events
      .through(log("Replicator"))
      .evalMap(handler.replicate(db)(_))
  }
}
