package backend.events

import backend.events.Subscriber.EventType
import simulacrum._

/**
  * Typeclass for things that have an [[EventType]]
  */
@typeclass
trait EventTypable[E] {
  def eventType(e: E): EventType
}
