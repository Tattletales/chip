import backend.gossip.Node.NodeId
import backend.network.Route
import cats.data.NonEmptyList

import backend.implicits._
import eu.timepit.refined.pureconfig._
import pureconfig._
import pureconfig.module.cats._
import eu.timepit.refined.pureconfig._

package object config {
  final case class ChipConfig(nodeIds: NonEmptyList[NodeId],
                              webSocketRoute: Route,
                              nodeIdRoute: Route,
                              logRoute: Route,
                              logFile: Option[String])

  object ChipConfig {
    val config: ChipConfig = loadConfigOrThrow[ChipConfig]("chip")
  }
}
