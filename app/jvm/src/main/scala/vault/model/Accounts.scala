package vault.model

import backend.events.Subscriber.Lsn
import cats.{Applicative, Functor, Monad, MonadError}
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
import vault.events.AccountsEvent.{AccountsEvent0, decodeAndCausalOrder}
import vault.events._
import vault.model.Account._

trait Accounts[F[_]] {
  def transfer(to: User, amount: Money): F[Unit]
  def balance(of: User): F[Money]
  def transactions(of: User): F[List[AccountsEvent]]
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
            daemon.send(Withdraw0(from, to, amount))
          else F.unit
        } yield ()

      def balance(of: User): F[Money] =
        kvs.get(of).map(_.getOrElse(initBalance(of, tag[MoneyTag][Double](100))))

      // Helper method which initializes an account with a balance of 100.0
      def initBalance(u: User, a: Money): Money = {
        kvs.put(u, a)
        a
      }

      // TODO: converting from List to Stream, and back to a List is a bit silly.
      def transactions(of: User): F[List[AccountsEvent]] = {
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
    }

  def mock[F[_]](kvs: KVStore[F, User, Money], daemon: GossipDaemon[F])(
      implicit F: Monad[F]): Accounts[F] = new Accounts[F] {
    def transfer(to: User, amount: Money): F[Unit] =
      (for {
        from <- OptionT.liftF(daemon.getNodeId)
        fromBalance <- OptionT(kvs.get(from))
        _ <- OptionT.liftF(kvs.put(from, tag[MoneyTag][Double](fromBalance - amount)))
        toBalance <- OptionT(kvs.get(to))
        _ <- OptionT.liftF(kvs.put(to, tag[MoneyTag][Double](toBalance + amount)))
      } yield ()).getOrElse(())

    def balance(of: User): F[Money] =
      kvs.get(of).map(_.getOrElse(tag[MoneyTag][Double](Double.MinValue)))

    def transactions(of: User): F[List[AccountsEvent]] = F.pure(List.empty)
  }
}
