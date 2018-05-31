package backend.events

import backend.gossip.Gossipable
import simulacrum.typeclass

/**
  * Typeclass representing causal things.
  */
@typeclass
trait CausalEvent[E] extends Gossipable[E] {
  def causedBy(e: E): Option[Lsn]
  def canCause(e: E): Boolean
}
