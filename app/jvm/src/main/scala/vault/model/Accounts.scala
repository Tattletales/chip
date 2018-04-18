package vault.model

import backend.events.Subscriber.Lsn
import cats.{Applicative, Foldable, Functor, Monad, MonadError}
import cats.implicits._
import backend.gossip.GossipDaemon
import backend.storage.{Database, KVStore, KeyNotFound}
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

trait Accounts[F[_]] {
  def transfer(to: User, amount: Money): F[Unit]
  def balance(of: User): F[Money]
  def transactions(of: User): F[List[AccountsEvent]]
  def withAccounts(u: User, us: User*): F[Accounts[F]]
}

object Accounts {
  def simple[F[_]: Effect](daemon: GossipDaemon[F], kvs: KVStore[F, User, Money])(
      implicit F: Monad[F]): Accounts[F] =
    new Accounts[F] {
      def transfer(to: User, amount: Money): F[Unit] =
        for {
          from <- daemon.getNodeId
          balanceOk <- balance(from).map(_ >= amount)
          _ <- if (balanceOk)
            daemon.send[AccountsEvent](Withdraw(from, to, amount))
          else F.unit
        } yield ()

      def balance(of: User): F[Money] =
        kvs.get(of).handleErrorWith {
          case KeyNotFound(_) => initBalance(of, tag[MoneyTag][Double](100))
        }

      // Helper method which initializes an account with a balance of 100.0
      def initBalance(u: User, a: Money): F[Money] =
        kvs.put(u, a) >> F.pure(a)

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
        kvs.put_*(initialBalance(u), us.map(initialBalance): _*) >> F.pure(this)

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

        fromBalance <- kvs
          .get(from)
          .adaptError {
            case KeyNotFound(_) => AccountNotFoundError(from)
          }

        _ <- kvs.put(from, tag[MoneyTag][Double](fromBalance - amount))

        toBalance <- kvs
          .get(to)
          .adaptError {
            case KeyNotFound(_) => AccountNotFoundError(to)
          }

        _ <- kvs.put(to, tag[MoneyTag][Double](toBalance + amount))
      } yield ()

    def balance(of: User): F[Money] =
      kvs.get(of).handleErrorWith {
        case KeyNotFound(_) =>
          initialBalance(of) match {
            case (u, m) => kvs.put(u, m) >> F.pure(m)
          }
      }

    def transactions(of: User): F[List[AccountsEvent]] = F.pure(List.empty)

    def withAccounts(u: User, us: User*): F[Accounts[F]] =
      kvs.put_*(initialBalance(u), us.map(initialBalance): _*) >> F.pure(this)

    private def initialBalance(u: User): (User, Money) = {
      val m = tag[MoneyTag][Double](100)
      (u, m)
    }
  }
}
