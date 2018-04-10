package vault.model

import backend.events.Subscriber.Lsn
import cats.Monad
import cats.implicits._
import backend.gossip.GossipDaemon
import backend.storage.Database
import doobie.implicits._
import vault.implicits._
import backend.implicits._
import io.circe.generic.auto._
import io.circe.Encoder
import fs2.Stream
import vault.events.AccountsEvent.AccountsEvent0
import vault.events._
import vault.model.Account._

trait Accounts[F[_]] {
  def transfer(to: User, amount: Money): F[Unit]
  def balance(of: User): F[Money]
  def transactions(of: User): F[List[AccountsEvent]]
}

object Accounts {
  def simple[F[_]](daemon: GossipDaemon[F], db: Database[F])(implicit F: Monad[F]): Accounts[F] =
    new Accounts[F] {
      def transfer(to: User, amount: Money): F[Unit] =
        for {
          from <- daemon.getNodeId
          balanceOk <- balance(from).map(_ >= amount)
          _ <- if (balanceOk)
            daemon.send(Withdraw0(from, to, amount))
          else F.unit
        } yield ()

      def balance(of: User): F[Money] = db.query[Money](sql"""
           SELECT balance
           FROM accounts
           WHERE holder = $of
         """).map(_.head) // TODO unsafe

      def transactions(of: User): F[List[AccountsEvent]] = {
        val events = Stream.force(daemon.getLog.map(es => Stream(es: _*).covary[F]))
        
        AccountsEvent.handler(daemon, db, this)(events)(???).filter(???)
        
        val s: Stream[F, AccountsEvent] = ???
        s.compile.toList
      }
    }
}
