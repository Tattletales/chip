package vault

import backend.gossip.Node.NodeId
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive

package object model {
  type User = NodeId
  type Money = Double Refined Positive
}
