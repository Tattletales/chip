package backend.events

import backend.gossip.Node.NodeId
import shapeless.tag.@@

object Event {
  sealed trait EventIdTag
  type EventId = Int @@ EventIdTag

  sealed trait EventTypeTag
  type EventType = String @@ EventTypeTag

  sealed trait PayloadTag
  type Payload = String @@ PayloadTag

  case class Lsn(nodeId: NodeId, eventId: EventId)
}
