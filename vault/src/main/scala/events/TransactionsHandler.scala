package vault.events

import backend.gossip.{GossipDaemon, Gossipable}
import backend.storage.KVStore
import cats.Applicative
import cats.effect.Effect
import backend.programs.Program
import vault.events.Transactions.handleTransactionStages
import vault.model.{Accounts, Money, User}
import fs2.Stream

object TransactionsHandler {

  /**
    * Create a [[Program]] that will consume the events delivered by the [[GossipDaemon]].
    */
  def apply[F[_]: Effect, E: Gossipable](
      daemon: GossipDaemon[F, TransactionStage, E],
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F])(implicit F: Applicative[F]): Program[F, Unit] =
    new Program[F, Unit] {
      def run: Stream[F, Unit] =
        daemon.subscribe.through(handleTransactionStages(_ => F.unit)(daemon, kvs, accounts))
    }

  /**
    * Create a [[Program]] that will consume the events delivered by the [[GossipDaemon]].
    * The `next` function can be used to specify what to do after a transaction has succeeded.
    */
  def withNext[F[_]: Effect, E: Gossipable](
      daemon: GossipDaemon[F, TransactionStage, E],
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F])(next: Deposit => F[Unit]): Program[F, Unit] =
    new Program[F, Unit] {
      def run: Stream[F, Unit] =
        daemon.subscribe.through(handleTransactionStages(next)(daemon, kvs, accounts))

    }
}
