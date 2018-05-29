package backend

import backend.gossip.Node.NodeId
import shapeless.tag.@@

package object events {
  sealed trait EventIdTag
  type EventId = Int @@ EventIdTag

  sealed trait EventTypeTag
  type EventType = String @@ EventTypeTag

  sealed trait PayloadTag
  type Payload = String @@ PayloadTag

  case class Lsn(nodeId: NodeId, eventId: EventId)
}
