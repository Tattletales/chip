import HandleEvents.Event
import io.circe.fs2._
import fs2._
import SseClient.{Event => SseEvent}
import TweetActions.TweetAction
import UsersActions.UsersAction
import cats.Applicative
import shapeless.{::, HNil}

object Replicator {
  def apply[F[_]: Applicative, User, Tweet](r: Repo[F, User, Tweet], events: Stream[F, SseEvent])(
    implicit handler: HandleEvents[TweetAction :: UsersAction :: HNil]
  ): Stream[F, Unit] = {
    val eventTypes = events.map(_.event)
    val payloads = events.map(_.payload).through(stringStreamParser)

    eventTypes.zipWith(payloads)(Event).flatMap(handler.handle(r)(_))
  }
}
