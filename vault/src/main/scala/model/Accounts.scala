package vault.model

import backend.errors.{NodeIdError, SendError}
import backend.gossip.GossipDaemon
import backend.storage.KVStore
import cats.MonadError
import cats.effect.Effect
import cats.implicits._
import eu.timepit.refined.api.RefType.{applyRef, applyRefM}
import eu.timepit.refined.auto._
import fs2.Stream
import backend.gossip.Gossipable
import cats.data.NonEmptyList
import vault.errors.{AccountNotFound, InsufficentFunds, TransferError, UnknownUser}
import vault.events.Transactions.decodeAndCausalOrder
import vault.events._

/**
  * Accounts DSL
  */
trait Accounts[F[_]] {

  /**
    * Add accounts of the given users.
    * Use this to create an [[Accounts]] with users.
    */
  def withAccounts(us: NonEmptyList[User]): F[Accounts[F]]

  /**
    * Transfer money from the owner of the current node to another node `to`.
    */
  def transfer(to: User, amount: Money): F[Unit]

  /**
    * Balance of the given user.
    */
  def balance(of: User): F[Money]

  /**
    * All transactions of the given user.
    */
  def transactions(of: User): F[List[TransactionStage]]
}

object Accounts {
  /* --- Interpreters ------ */

  /**
    * Interpreter to [[GossipDaemon]] and [[KVStore]] DSLs.
    */
  def default[F[_], E: Gossipable](
      daemon: GossipDaemon[F, TransactionStage, E],
      kvs: KVStore[F, User, Money])(implicit F: Effect[F]): Accounts[F] =
    new Accounts[F] {

      /**
        * @see [[Accounts.transfer]]
        *
        *      Failures:
        *   - [[InsufficentFunds]] if there are not sufficient funds in the account to transfer from.
        *   - [[UnknownUser]] if the current user cannot be determined.
        *   - [[TransferError]] if the transfer cannot be initialized.
        */
      def transfer(to: User, amount: Money): F[Unit] =
        for {
          from <- daemon.getNodeId.adaptError { case NodeIdError => UnknownUser }

          _ <- balance(from).ensureOr(cAmount => InsufficentFunds(cAmount, from))(
            _.value >= amount.value)

          _ <- daemon.send(Withdraw(from, to, amount)).adaptError {
            case SendError => TransferError
          }
        } yield ()

      /**
        * @see [[Accounts.balance]]
        *
        * Fails with [[AccountNotFound]] if the account does not exist.
        */
      def balance(of: User): F[Money] =
        kvs.get(of).flatMap(F.fromOption(_, AccountNotFound(of)))

      // TODO: converting from List to Stream, and back to a List is a bit silly.
      /**
        * @see [[Accounts.transactions]]
        */
      def transactions(of: User): F[List[TransactionStage]] = {
        val events = Stream.force(daemon.getLog.map(es => Stream(es: _*).covary[F]))

        events
          .through(decodeAndCausalOrder(this))
          .filter { // Keep transactions related to the user
            case Withdraw(from, _, _, _) => from == of
            case Deposit(_, to, _, _)    => to == of
          }
          .compile
          .toList
      }

      /**
        * @see [[Accounts.withAccounts]]
        */
      def withAccounts(us: NonEmptyList[User]): F[Accounts[F]] =
        us.map(initialBalance).traverse { case (k, v) => kvs.put(k, v) } >> F.pure(this)

      /**
        * Initial balance of 100
        */
      private def initialBalance(u: User): (User, Money) = {
        val m = applyRefM[Money](100.0)
        (u, m)
      }
    }

  def mock[F[_], E](daemon: GossipDaemon[F, TransactionStage, E], kvs: KVStore[F, User, Money])(
      implicit F: MonadError[F, Throwable]): Accounts[F] = new Accounts[F] {

    /**
      * Transfer money from the current user's account to the given user.
      *
      * Warning: it does not check if there are sufficient funds in the account.
      */
    def transfer(to: User, amount: Money): F[Unit] = {
      val debitFrom = for {
        from <- daemon.getNodeId
        fromBalance <- balance(from)
        newFromBalance <- F.fromEither(
          applyRef[Money](fromBalance.value - amount.value).leftMap(_ => InsufficentFunds(fromBalance, from)))
        _ <- kvs.put(from, newFromBalance)
      } yield ()

      val creditFrom = for {
        toBalance <- balance(to)
        newToBalance <- F.fromEither(
          applyRef[Money](toBalance.value + amount.value).leftMap(_ => InsufficentFunds(toBalance, to)))
        _ <- kvs.put(to, newToBalance)
      } yield ()

      debitFrom *> creditFrom
    }

    /**
      * Balance of the given user's accounts.
      *
      * Fails with [[AccountNotFound]] if the account does not exist.
      */
    def balance(of: User): F[Money] =
      kvs.get(of).flatMap(F.fromOption(_, AccountNotFound(of)))

    /**
      * Warning: just an empty list
      */
    def transactions(of: User): F[List[TransactionStage]] = F.pure(List.empty)

    def withAccounts(us: NonEmptyList[User]): F[Accounts[F]] =
      us.map(initialBalance).traverse { case (k, v) => kvs.put(k, v) } >> F.pure(this)

    private def initialBalance(u: User): (User, Money) = {
      val m = applyRefM[Money](100.0)
      (u, m)
    }
  }
}
