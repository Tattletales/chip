package backend
package gossip

import simulacrum._
import events.{Lsn, Payload}

/**
  * Typeclass for things that can be gossiped.
  */
@typeclass
trait Gossipable[E] {
  def lsn(e: E): Lsn
  def payload(e: E): Payload
}
