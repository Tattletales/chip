package backend.events

import backend.gossip.Gossipable
import simulacrum.typeclass

/**
  * Typeclass representing causal things.
  */
@typeclass
trait CausalEvent[E] extends Gossipable[E] {

  /**
    * `Lsn` of the event that caused `e` if it has any.
    */
  def causedBy(e: E): Option[Lsn]

  /**
    * True if other events can depend on it.
    *
    * This is used to release events from the causal order buffer.
    */
  def canCause(e: E): Boolean
}
