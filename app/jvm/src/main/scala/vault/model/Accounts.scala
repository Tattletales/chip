package vault.model

import cats.Monad
import cats.implicits._
import gossip.GossipDaemon
import io.circe.generic.auto._
import storage.Database
import vault.model.implicits._
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
        _ <- if (balanceOk) daemon.send[AccountsEvent](Withdraw(from, to, amount)) else F.unit
      } yield ()

    def balance(of: User): F[Money] = ???
  }
}
