package vault.model

import cats.Monad
import cats.implicits._
import backend.gossip.GossipDaemon
import backend.storage.Database
import vault.implicits._
import backend.implicits._
import io.circe.generic.auto._
import io.circe.Encoder
import vault.events.AccountsEvent.AccountsEvent0
import vault.events._
import vault.model.Account._

trait Accounts[F[_]] {
  def transfer(to: User, amount: Money): F[Unit]
  def balance(of: User): F[Money]
}

object Accounts {
  def simple[F[_]](daemon: GossipDaemon[F], database: Database[F])(
      implicit F: Monad[F]): Accounts[F] = new Accounts[F] {
    def transfer(to: User, amount: Money): F[Unit] =
      for {
        from <- daemon.getNodeId
        balanceOk <- balance(from).map(_ >= amount)
        _ <- if (balanceOk)
          daemon.send[AccountsEvent0](Left(Withdraw0(from, to, amount)))
        else F.unit
      } yield ()

    def balance(of: User): F[Money] = ???
  }
}
