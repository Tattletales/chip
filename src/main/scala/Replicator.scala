import HandleEvents.Event
import io.circe.fs2._
import fs2._
import SseClient.{SSEvent => SseEvent}
import TweetActions.TweetsAction
import UsersActions.UsersAction
import cats.Applicative
import shapeless.{::, HNil}
import HandleEvents.{baseCase, inductionStep}

object Replicator {
  def apply[F[_]: Applicative, Events](r: Repo[F], events: Stream[F, SseEvent]): Stream[F, Unit] = {
    val handler = implicitly[HandleEvents[Events]]

    val eventTypes = events.map(_.event)
    val payloads = events.map(_.payload).through(stringStreamParser)

    eventTypes.zipWith(payloads)(Event).flatMap(handler.handle(r)(_))
  }
}
