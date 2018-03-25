package vault.events

import cats.{Applicative, Parallel}
import cats.effect.Effect
import backend.events.EventTyper
import backend.events.Subscriber.{EventType, EventTypeTag}
import backend.gossip.GossipDaemon
import backend.gossip.model.Node.NodeId
import shapeless.tag
import io.circe.generic.auto._
import vault.implicits._
import backend.implicits._
import backend.storage.Database
import vault.model.Account.{Money, User}
import cats.implicits._
import vault.model.Accounts

sealed trait AccountsEvent
case class Withdraw(from: User, to: User, amount: Money) extends AccountsEvent
case class Deposit(from: User, to: User, fa: Money) extends AccountsEvent

object AccountsEvent {
  implicit val eventTypable: EventTyper[AccountsEvent] = new EventTyper[AccountsEvent] {
    def eventType: EventType = tag[EventTypeTag][String]("accounts")
  }

  implicit val trace: Trace[AccountsEvent] = new Trace[AccountsEvent] {
    def sender(e: AccountsEvent): NodeId = e match {
      case Withdraw(from, _, _) => from
      case Deposit(from, _, _) => from
    }
  }

  implicit def replicable[F[_]]: Replicator[AccountsEvent] = new Replicator[AccountsEvent] {
    def replicate[F[_]](
        sender: NodeId,
        db: Database[F],
        daemon: GossipDaemon[F],
        accounts: Accounts[F])(event: AccountsEvent)(implicit F: Effect[F]): F[Unit] =
      event match {
        case Withdraw(from, to, amount) =>
          val balanceOk = accounts.balance(from).map(_ >= amount)

          val deposit =
            daemon.getNodeId
              .map(_ == to)
              .ifM(daemon.send[AccountsEvent](Deposit(from, to, amount)), F.unit)

          val updateBalance: F[Unit] = ???

          // Run both in parallel. Fails if one fails.
          val depositAndUpdateBalance = deposit.map2(updateBalance) { (_, _) =>
            ()
          }

          balanceOk.ifM(depositAndUpdateBalance, F.unit)

        case Deposit(from, to, amount) =>
          ??? // updateBalance
      }
  }
}
