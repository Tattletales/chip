package backend

import backend.gossip.Node.NodeId
import cats.effect.Effect
import io.circe.Json
import shapeless.tag.@@

package object events {
  sealed trait EventIdTag
  type EventId = Int @@ EventIdTag

  sealed trait EventTypeTag
  type EventType = String @@ EventTypeTag

  sealed trait PayloadTag
  type Payload = Json @@ PayloadTag

  final case class Lsn(nodeId: NodeId, eventId: EventId)

  def subscription[F[_]: Effect]: Subscription[F, SSEvent] = Subscription.serverSentEvent
}
