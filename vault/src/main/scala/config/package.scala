package vault

import backend.gossip.Node.NodeId
import backend.network.Route
import backend.implicits._
import cats.data.NonEmptyList
import pureconfig.loadConfigOrThrow
import vault.benchmarks.Benchmark

import eu.timepit.refined.pureconfig._
import pureconfig._
import pureconfig.module.cats._
import eu.timepit.refined.pureconfig._

package object config {
  final case class VaultConfig(nodeIds: NonEmptyList[NodeId],
                               webSocketRoute: Route,
                               nodeIdRoute: Route,
                               logRoute: Route,
                               benchmark: Option[Benchmark],
                               logFile: Option[String])

  object VaultConfig {
    val config: VaultConfig = loadConfigOrThrow[VaultConfig]("vault")
  }
}
