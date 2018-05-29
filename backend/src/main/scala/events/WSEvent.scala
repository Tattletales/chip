package backend.events

import backend.gossip.Gossipable

/**
  * Event sent by the WebSocket protocol.
  */
case class WSEvent(lsn: Lsn, payload: Payload)
object WSEvent {
  implicit def gossipableWSEvent: Gossipable[WSEvent] = new Gossipable[WSEvent] {
    def lsn(e: WSEvent): Lsn = e.lsn
    def payload(e: WSEvent): Payload = e.payload
  }
}
