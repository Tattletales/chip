package vault.events

import cats.Monad
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

object AccountsEvent {
  // Both Events can be received.
  type AccountsEvent0 = Either[Withdraw0, Deposit]

  def handleEvents[F[_]: Effect](daemon: GossipDaemon[F], db: Database[F], accounts: Accounts[F])(
      s: Stream[F, Event]): Stream[F, Unit] =
    handler(daemon, db, accounts)(s)(handleEvent(daemon, db, accounts))

  def handler[F[_]: Effect, O](daemon: GossipDaemon[F], db: Database[F], accounts: Accounts[F])(
      s: Stream[F, Event])(f: AccountsEvent => F[O]): Stream[F, O] =
    s.through(decode).through(causal(accounts)).evalMap(f)
  
  
  //def foo[F[_]: Effect] = {
  //  val lf: F[List[Event]] = ???
  //  lf.map(_.map(decode))
  //}
  
  /**
    * Converts Events in AccountsEvents.
    * Ignores AccountsEvents with mismatching senders.
    */
  private def decode[F[_]: Monad]: Pipe[F, Event, AccountsEvent] = {
    def convert(e: Event): Option[AccountsEvent] =
      circeDecode[AccountsEvent0](e.payload)
        .map {
          case Right(deposit)                    => deposit
          case Left(Withdraw0(from, to, amount)) => Withdraw(from, to, amount, e.lsn)
        }
        .toOption
        .filter {
          case Withdraw(from, _, _, _) => e.lsn.nodeId == from
          case Deposit(from, _, _, _)  => e.lsn.nodeId == from
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
  private def handleEvent[F[_]](daemon: GossipDaemon[F], db: Database[F], accounts: Accounts[F])(
      event: AccountsEvent)(implicit F: Effect[F]): F[Unit] =
    event match {
      case Withdraw(from, to, amount, lsn) =>
        val deposit = daemon.send[AccountsEvent](Deposit(from, to, amount, lsn))

        daemon.getNodeId.map(_ == to).ifM(deposit, F.unit)

      case Deposit(from, to, amount, dependsOn) =>
        // Transfer the money. Succeeds only if both succeeded.
        F.unit.map2(F.unit)((_, _) => ())
    }

  /**
    * Not used in Vault.
    */
  implicit val eventTyper: EventTyper[AccountsEvent] = new EventTyper[AccountsEvent] {
    def eventType: EventType = tag[EventTypeTag][String]("AccountsEvent0")
  }
}

/**
  * Withdraw event when the Lsn is not yet known.
  */
case class Withdraw0(from: User, to: User, amount: Money)

object Withdraw0 {

  /**
    * Not used in Vault.
    */
  implicit val eventTyper: EventTyper[Withdraw0] = new EventTyper[Withdraw0] {
    def eventType: EventType = tag[EventTypeTag][String]("AccountsEvent0")
  }
}
