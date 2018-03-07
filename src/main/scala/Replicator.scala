import HandleEvents.{baseCase, inductionStep}
import SseClient.Event
import TweetsActions.TweetsAction
import UsersActions.UsersAction
import Utils.log
import cats.effect.Effect
import io.circe.generic.auto._
import fs2._
import shapeless.{::, HNil}

object Replicator {
  def apply[F[_]: Effect](db: Database[F], events: Stream[F, Event]): Stream[F, Unit] = {
    val handler = implicitly[HandleEvents[TweetsAction :: UsersAction :: HNil]]

    events
      .through(log("Replicator"))
      .evalMap(handler.handle(db)(_))
  }
}
