package gossipServer

import backend.gossip.Node.NodeId
import cats.data.NonEmptyList

package object config {
  final case class ServerConfig(nodeIds: NonEmptyList[NodeId])
}
