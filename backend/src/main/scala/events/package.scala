package backend

import backend.gossip.Node.NodeId
import io.circe.Json
import shapeless.tag.@@

package object events {
  sealed trait EventIdTag
  type EventId = Int @@ EventIdTag

  sealed trait EventTypeTag
  type EventType = String @@ EventTypeTag

  sealed trait PayloadTag
  type Payload = Json @@ PayloadTag

  case class Lsn(nodeId: NodeId, eventId: EventId)
}
