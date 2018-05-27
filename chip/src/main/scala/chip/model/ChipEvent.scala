package chip.model

import backend.events.Event.{EventType, EventTypeTag}
import backend.events.EventTyper
import chip.model.TweetsEvents.TweetsEvent
import chip.model.UsersEvents.UsersEvent
import io.circe.Encoder
import io.circe.Encoder._
import shapeless.tag

object ChipEvent {
  type ChipEvent = Either[TweetsEvent, UsersEvent]

  implicit def chipEventEventTyper(implicit T: EventTyper[TweetsEvent],
                                   U: EventTyper[UsersEvent]): EventTyper[ChipEvent] =
    new EventTyper[ChipEvent] {
      def eventType: EventType = tag[EventTypeTag][String](s"${T.eventType}-${U.eventType}")
    }

  implicit def encoder(implicit tweetsEncoder: Encoder[TweetsEvent],
                       usersEncoder: Encoder[UsersEvent]): Encoder[ChipEvent] =
    encodeEither("tweets", "users")
}
