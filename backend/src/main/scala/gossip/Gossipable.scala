package gossip

import backend.events.Subscriber.{Lsn, Payload}
import simulacrum._

/**
  * Typeclass for things that can be gossiped.
  */
@typeclass
trait Gossipable[E] {
  def lsn(e: E): Lsn
  def payload(e: E): Payload
}
