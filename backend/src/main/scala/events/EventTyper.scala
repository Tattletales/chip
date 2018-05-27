package backend.events

import backend.events.Event.EventType
import simulacrum._

/**
  * Typeclass providing the [[EventType]]
  */
@typeclass
trait EventTyper[T] {
  def eventType: EventType
}
