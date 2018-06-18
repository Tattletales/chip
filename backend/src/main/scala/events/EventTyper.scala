package backend
package events

import simulacrum._

/**
  * Typeclass providing the [[EventType]]
  */
@typeclass
trait EventTyper[T] {
  def eventType: EventType
}
