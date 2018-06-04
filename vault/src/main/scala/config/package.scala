package vault

import backend.gossip.Node.NodeId
import backend.network.Route
import cats.data.NonEmptyList
import vault.benchmarks.Benchmark

package object config {
  final case class VaultConfig(nodeIds: NonEmptyList[NodeId],
                               webSocketRoute: Route,
                               nodeIdRoute: Route,
                               logRoute: Route,
                               benchmark: Option[Benchmark])
}
