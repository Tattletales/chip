package events

import events.Subscriber.EventType
import simulacrum._

@typeclass
trait EventTyper[T] {
  def eventType: EventType
}
