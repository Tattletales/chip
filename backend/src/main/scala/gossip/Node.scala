package backend
package gossip

import shapeless.tag.@@

object Node {
  sealed trait NodeIdTag
  type NodeId = String @@ NodeIdTag
}
