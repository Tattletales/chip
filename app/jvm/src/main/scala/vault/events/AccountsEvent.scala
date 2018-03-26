package vault.events

import cats.{Functor, Monad}
import cats.effect.Effect
import cats.implicits._
import backend.events.EventTyper
import backend.events.Subscriber.{Event, EventType, EventTypeTag, Lsn}
import backend.gossip.GossipDaemon
import backend.gossip.model.Node.NodeId
import shapeless.{:+:, CNil, Inl, Inr, tag}
import fs2.{Pipe, Pull, Sink, Stream}
import fs2.Stream.InvariantOps
import io.circe.generic.auto._
import io.circe.parser.{decode => circeDecode}
import vault.implicits._
import backend.implicits._
import backend.storage.Database
import vault.model.Account.{Money, User}
import vault.model.Accounts

sealed trait AccountsEvent
case class Withdraw(from: User, to: User, amount: Money, lsn: Lsn) extends AccountsEvent
case class Deposit(from: User, to: User, amount: Money, dependsOn: Lsn) extends AccountsEvent

case class Withdraw0(from: User, to: User, amount: Money)

object AccountsEvent {
  type AccountsEvent0 = Either[Withdraw0, AccountsEvent]

  def run[F[_]: Effect](sender: NodeId,
                        db: Database[F],
                        daemon: GossipDaemon[F],
                        accounts: Accounts[F]): Sink[F, Event] =
    _.through(decode).through(causal(accounts)).evalMap(replicate(db, daemon, accounts)(_))

  /**
    * Converts Events in AccountsEvents.
    * Ignores AccountsEvents with mismatching senders.
    */
  private def decode[F[_]: Monad]: Pipe[F, Event, AccountsEvent] = {
    def convert(e: Event): Option[AccountsEvent] =
      circeDecode[AccountsEvent0](e.payload)
        .map {
          case Right(a) => a
          case Left(Withdraw0(from, to, amount)) => Withdraw(from, to, amount, e.lsn)
        }
        .toOption
        .flatMap {
          case w @ Withdraw(from, _, _, _) if e.lsn.nodeId == from => Some(w)
          case d @ Deposit(from, _, _, _) if e.lsn.nodeId == from => Some(d)
          case _ => None
        }

    _.map(convert).unNone
  }

  /**
    * Causal ordering of AccountsEvents.
    * Ignores AccountsEvents that are invalid:
    *   - Deposited and withdrawn amounts do not match.
    *   - Balance is insufficent.
    */
  private def causal[F[_]](accounts: Accounts[F])(
      implicit F: Monad[F]): Pipe[F, AccountsEvent, AccountsEvent] = {
    def go(s: Stream[F, AccountsEvent],
           accounts: Accounts[F],
           waitingFor: Map[Lsn, Deposit],
           withdrawn: Map[Lsn, Money]): Pull[F, AccountsEvent, Unit] =
      Stream.InvariantOps(s).pull.uncons1.flatMap {
        case Some((e, es)) =>
          e match {
            case d @ Deposit(from, _, depositedAmount, dependsOn) =>
              val handleDeposit = withdrawn.get(dependsOn) match {
                case Some(withdrawnAmount) =>
                  if (depositedAmount == withdrawnAmount) {
                    Pull.output1(d) >> go(es, accounts, waitingFor, withdrawn - dependsOn)
                  } else {
                    go(es, accounts, waitingFor, withdrawn) // Ignore, transaction is faulty.
                  }
                case None =>
                  // Wait for the related Withdrawn to be arrive.
                  go(es, accounts, waitingFor + (dependsOn -> d), withdrawn)
              }

              for {
                balanceOk <- Pull.eval(accounts.balance(from).map(_ >= depositedAmount))
                _ <- if (balanceOk) handleDeposit else Pull.done
              } yield ()

            case w @ Withdraw(from, _, withdrawnAmount, lsn) =>
              val handleWithdraw = waitingFor.get(lsn) match {
                case Some(deposit) =>
                  if (deposit.amount == withdrawnAmount) {
                    Pull.output1(w) >> Pull
                      .output1(deposit) >> go(es, accounts, waitingFor - lsn, withdrawn)
                  } else {
                    // Ignore, transaction is faulty.
                    go(es, accounts, waitingFor - lsn, withdrawn)
                  }
                case None =>
                  // Output the Withdraw and note how much it has withdrawn in order
                  // to check if the related Deposit is valid.
                  Pull.output1(w) >> go(es,
                                        accounts,
                                        waitingFor,
                                        withdrawn + (lsn -> withdrawnAmount))
              }

              for {
                balanceOk <- Pull.eval(accounts.balance(from).map(_ >= withdrawnAmount))
                _ <- if (balanceOk) handleWithdraw else Pull.done
              } yield ()
          }
        case None => Pull.done
      }

    go(_, accounts, Map.empty, Map.empty).stream
  }

  /**
    * Handles AccountsEvents.
    */
  private def replicate[F[_]](db: Database[F], daemon: GossipDaemon[F], accounts: Accounts[F])(
      event: AccountsEvent)(implicit F: Effect[F]): F[Unit] =
    event match {
      case Withdraw(from, to, amount, lsn) => ???
      case Deposit(from, to, amount, dependsOn) => ???
    }

  /**
    * Not used in Vault.
    */
  implicit val eventTyper: EventTyper[AccountsEvent0] = new EventTyper[AccountsEvent0] {
    def eventType: EventType = tag[EventTypeTag][String]("AccountsEvent0")
  }
}
