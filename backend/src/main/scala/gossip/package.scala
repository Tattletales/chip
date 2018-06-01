package backend

import backend.events.{EventTyper, WSEvent}
import backend.gossip.GossipDaemon
import backend.gossip.Node.NodeId
import backend.network.{HttpClient, Route, WebSocketClient}
import cats.MonadError
import cats.effect.Effect
import io.circe.Encoder

package object gossip {
  def gossipDaemon[F[_], E: Encoder](nodeIdRoute: Route, logRoute: Route)(
      nodeId: Option[NodeId] = None)(httpClient: HttpClient[F], ws: WebSocketClient[F, E, WSEvent])(
      implicit F: MonadError[F, Throwable],
      E: EventTyper[E]): GossipDaemon[F, E, WSEvent] =
    GossipDaemon.webSocket(nodeIdRoute, logRoute)(nodeId)(httpClient, ws)
  
  def logToFile[F[_]: Effect, E1, E2](path: String)(daemon: GossipDaemon[F, E1, E2]): GossipDaemon[F, E1, E2] = GossipDaemon.logging(path)(daemon)
}
