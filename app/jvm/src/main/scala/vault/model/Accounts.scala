package vault.model

import backend.gossip.GossipDaemon
import backend.gossip.GossipDaemon.{NodeIdError, SendError}
import backend.storage.KVStore
import backend.implicits._
import cats.MonadError
import cats.effect.Effect
import cats.implicits._
import fs2.Stream
import io.circe.generic.auto._
import shapeless.tag
import vault.events.Transactions.decodeAndCausalOrder
import vault.events._
import vault.implicits._
import vault.model.Account._

/**
  * Accounts DSL
  */
trait Accounts[F[_]] {

  /**
    * Add accounts of the given users.
    * Use this to create an [[Accounts]] with users.
    */
  def withAccounts(u: User, us: User*): F[Accounts[F]]

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
  def default[F[_]](daemon: GossipDaemon[F], kvs: KVStore[F, User, Money])(
      implicit F: Effect[F]): Accounts[F] =
    new Accounts[F] {

      /**
        * @see [[Accounts.transfer]]
        *
        * Failures:
        *   - [[UnsufficentFunds]] if there are not sufficient funds in the account to transfer from.
        *   - [[UnknownUser]] if the current user cannot be determined.
        *   - [[TransferError]] if the transfer cannot be initialized.
        */
      def transfer(to: User, amount: Money): F[Unit] =
        for {
          from <- daemon.getNodeId.adaptError { case NodeIdError => UnknownUser }

          _ <- balance(from).ensureOr(cAmount => UnsufficentFunds(cAmount, from))(_ >= amount)

          _ <- daemon.send[TransactionStage](Withdraw(from, to, amount)).adaptError {
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
      def withAccounts(u: User, us: User*): F[Accounts[F]] =
        kvs.put_*(initialBalance(u), us.map(initialBalance): _*) >> F.pure(this)

      /**
        * Initial balance of 100
        */
      private def initialBalance(u: User): (User, Money) = {
        val m = tag[MoneyTag][Double](100)
        (u, m)
      }
    }

  def mock[F[_]](daemon: GossipDaemon[F], kvs: KVStore[F, User, Money])(
      implicit F: MonadError[F, Throwable]): Accounts[F] = new Accounts[F] {

    /**
      * Transfer money from the current user's account to the given user.
      *
      * Warning: it does not check if there are sufficient funds in the account.
      */
    def transfer(to: User, amount: Money): F[Unit] =
      for {
        from <- daemon.getNodeId

        fromBalance <- balance(from)

        _ <- kvs.put(from, tag[MoneyTag][Double](fromBalance - amount))

        toBalance <- balance(to)

        _ <- kvs.put(to, tag[MoneyTag][Double](toBalance + amount))
      } yield ()

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

    def withAccounts(u: User, us: User*): F[Accounts[F]] =
      kvs.put_*(initialBalance(u), us.map(initialBalance): _*) >> F.pure(this)

    private def initialBalance(u: User): (User, Money) = {
      val m = tag[MoneyTag][Double](100)
      (u, m)
    }
  }

  /* ------ Errors ------ */
  sealed trait AccountsError extends Throwable
  case class AccountNotFound(user: User) extends AccountsError {
    override def toString: String = s"No account for $user."
  }
  case class UnsufficentFunds(currentAmount: Money, of: User) extends AccountsError
  case object UnknownUser extends AccountsError
  case object TransferError extends AccountsError

}
