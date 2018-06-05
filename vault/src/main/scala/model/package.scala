package vault

import backend.gossip.{GossipDaemon, Gossipable}
import backend.gossip.Node.NodeId
import backend.storage.KVStore
import cats.data.NonEmptyList
import cats.effect.Effect
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.NonNegative
import vault.events.TransactionStage

package object model {
  type User = NodeId
  type Money = Double Refined NonNegative

  /**
    * Create a an [[Accounts]] with the accounts of the provided users.
    */
  def accounts[F[_]: Effect, E: Gossipable](us: NonEmptyList[User])(
      daemon: GossipDaemon[F, TransactionStage, E],
      kvs: KVStore[F, User, Money]): F[Accounts[F]] =
    Accounts.default(daemon, kvs).withAccounts(us)
}
