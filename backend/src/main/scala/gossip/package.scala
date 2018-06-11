package backend

import backend.events.WSEvent
import backend.gossip.Node.NodeId
import backend.network.{HttpClient, Route, WebSocketClient}
import cats.MonadError
import cats.effect.Effect
import io.circe.Encoder

package object gossip {
  /**
    * Create a [[GossipDaemon]] using the WebSocket protocol.
    */
  def gossipDaemon[F[_]: MonadError[?[_], Throwable], E: Encoder](nodeIdRoute: Route,
                                                                  logRoute: Route)(nodeId: NodeId)(
      httpClient: HttpClient[F],
      ws: WebSocketClient[F, E, WSEvent]): GossipDaemon[F, E, WSEvent] =
    GossipDaemon.webSocket(nodeIdRoute, logRoute)(Some(nodeId))(httpClient, ws)

  def logToFile[F[_]: Effect, E1, E2](path: String)(
      daemon: GossipDaemon[F, E1, E2]): GossipDaemon[F, E1, E2] = GossipDaemon.logging(path)(daemon)
}
