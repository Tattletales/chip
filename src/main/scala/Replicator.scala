import HandleEvents.{Event, baseCase, inductionStep}
import SseClient.{SSEvent => SseEvent}
import TweetsActions.TweetsAction
import UsersActions.UsersAction
import cats.effect.Effect
import io.circe.fs2._
import io.circe.generic.auto._
import fs2._
import shapeless.{::, HNil}

object Replicator {
  def apply[F[_]: Effect](db: Database[F], events: Stream[F, SseEvent]): Stream[F, Unit] = {
    val handler = implicitly[HandleEvents[TweetsAction :: UsersAction :: HNil]]

    val eventTypes = events.map(_.event)
    val payloads = events.map(_.payload).through(stringStreamParser)

    eventTypes.zipWith(payloads)(Event).map(handler.handle(db)(_))
  }
}
