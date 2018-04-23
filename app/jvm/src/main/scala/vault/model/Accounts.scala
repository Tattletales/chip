package vault.model

import backend.events.Subscriber.Lsn
import cats.{Applicative, Foldable, Functor, Monad, MonadError}
import cats.implicits._
import backend.gossip.GossipDaemon
import backend.storage.{Database, KVStore}
import doobie.implicits._
import vault.implicits._
import backend.implicits._
import cats.data.OptionT
import cats.effect.Effect
import io.circe.generic.auto._
import io.circe.Encoder
import fs2.Stream
import shapeless.tag
import vault.events.AccountsEvent.decodeAndCausalOrder
import vault.events._
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
  def transactions(of: User): F[List[AccountsEvent]]
}

object Accounts {
  /* --- Interpreters ------ */

  /**
    * Interpreter to [[GossipDaemon]] and [[KVStore]] DSLs.
    */
  def simple[F[_]: Effect](daemon: GossipDaemon[F], kvs: KVStore[F, User, Money]): Accounts[F] =
    new Accounts[F] {
      def transfer(to: User, amount: Money): F[Unit] =
        for {
          from <- daemon.getNodeId
          balanceOk <- balance(from).map(_ >= amount)
          _ <- if (balanceOk)
            daemon.send[AccountsEvent](Withdraw(from, to, amount))
          else implicitly[Applicative[F]].unit
        } yield ()

      def balance(of: User): F[Money] =
        kvs
          .get(of)
          .flatMap(implicitly[MonadError[F, Throwable]].fromOption(_, AccountNotFound(of)))

      // TODO: converting from List to Stream, and back to a List is a bit silly.
      def transactions(of: User): F[List[AccountsEvent]] = {
        val events = Stream.force(daemon.getLog.map(es => Stream(es: _*).covary[F]))

        events
          .through(decodeAndCausalOrder(this))
          .filter { // Keep transactions related to the user
            case Withdraw(from, _, _, _) => from == of
            case Deposit(_, to, _, _) => to == of
          }
          .compile
          .toList
      }

      def withAccounts(u: User, us: User*): F[Accounts[F]] =
        kvs.put_*(initialBalance(u), us.map(initialBalance): _*) >> implicitly[Applicative[F]]
          .pure(this)

      private def initialBalance(u: User): (User, Money) = {
        val m = tag[MoneyTag][Double](100)
        (u, m)
      }
    }

  def mock[F[_]](daemon: GossipDaemon[F], kvs: KVStore[F, User, Money])(
      implicit F: MonadError[F, Throwable]): Accounts[F] = new Accounts[F] {
    def transfer(to: User, amount: Money): F[Unit] =
      for {
        from <- daemon.getNodeId

        fromBalance <- balance(from)

        _ <- kvs.put(from, tag[MoneyTag][Double](fromBalance - amount))

        toBalance <- balance(to)

        _ <- kvs.put(to, tag[MoneyTag][Double](toBalance + amount))
      } yield ()

    def balance(of: User): F[Money] =
      kvs.get(of).flatMap(F.fromOption(_, AccountNotFound(of)))

    def transactions(of: User): F[List[AccountsEvent]] = F.pure(List.empty)

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
}
