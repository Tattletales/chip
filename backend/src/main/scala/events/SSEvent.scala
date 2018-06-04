package backend.events

import backend.gossip.Gossipable

/**
  * Event sent by the ServerSentEvent protocol
  */
final case class SSEvent(lsn: Lsn, eventType: EventType, payload: Payload)
object SSEvent {
  implicit def gossipableSSEvent: Gossipable[SSEvent] = new Gossipable[SSEvent] {
    def lsn(e: SSEvent): Lsn = e.lsn
    def payload(e: SSEvent): Payload = e.payload
  }

  implicit def eventTypable: EventTypable[SSEvent] = new EventTypable[SSEvent] {
    def eventType(e: SSEvent): EventType = e.eventType
  }
}
