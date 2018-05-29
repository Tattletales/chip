package backend.events

import simulacrum._

/**
  * Typeclass for things that have an [[EventType]]
  */
@typeclass
trait EventTypable[E] {
  def eventType(e: E): EventType
}
