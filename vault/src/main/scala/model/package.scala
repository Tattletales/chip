package vault

import backend.gossip.{GossipDaemon, Gossipable}
import backend.gossip.Node.NodeId
import backend.storage.KVStore
import cats.effect.Effect
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import vault.events.TransactionStage

package object model {
  type User = NodeId
  type Money = Double Refined Positive

  def accounts[F[_]: Effect, E: Gossipable](daemon: GossipDaemon[F, TransactionStage, E],
                                            kvs: KVStore[F, User, Money]): Accounts[F] =
    Accounts.default(daemon, kvs)
}
