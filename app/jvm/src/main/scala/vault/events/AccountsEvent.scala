package vault.events

import cats.{Functor, Monad, MonadError}
import cats.effect.Effect
import cats.implicits._
import backend.events.EventTyper
import backend.events.Subscriber.{Event, EventType, EventTypeTag, Lsn}
import backend.gossip.GossipDaemon
import backend.gossip.model.Node.NodeId
import shapeless.tag
import fs2.{Pipe, Pull, Sink, Stream}
import fs2.Stream.InvariantOps
import io.circe.generic.auto._
import io.circe.parser.{decode => circeDecode}
import vault.implicits._
import backend.implicits._
import backend.storage.{Database, KVStore}
import cats.data.OptionT
import vault.model.Account.{Money, MoneyTag, User}
import vault.model.Accounts
import doobie.implicits._

/**
  * A transaction is split into two events Withdraw and Deposit so the events only relate
  * to the account of the sender.
  *
  * The Deposit event depends on the Withdraw through the latter's lsn.
  */
sealed trait AccountsEvent
case class Withdraw(from: User, to: User, amount: Money, lsn: Lsn) extends AccountsEvent
case class Deposit(from: User, to: User, amount: Money, dependsOn: Lsn) extends AccountsEvent

object AccountsEvent {
  // Both Events can be received.
  type AccountsEvent0 = Either[Withdraw0, Deposit]

  def handleAccountsEvents[F[_]: Effect](daemon: GossipDaemon[F],
                                         kvs: KVStore[F, User, Money],
                                         accounts: Accounts[F]): Sink[F, Event] =
    _.through(decodeAndCausalOrder(accounts)).evalMap(handleEvent(daemon, kvs, accounts))

  def decodeAndCausalOrder[F[_]: Effect, O](accounts: Accounts[F]): Pipe[F, Event, AccountsEvent] =
    _.through(decode).through(causalOrder(accounts))

  /**
    * Converts Events in AccountsEvents.
    * Ignores AccountsEvents with mismatching senders.
    */
  private def decode[F[_]: Monad]: Pipe[F, Event, AccountsEvent] = {
    def convert(e: Event): Option[AccountsEvent] =
      circeDecode[AccountsEvent0](e.payload)
        .map {
          case Right(deposit) => deposit
          case Left(Withdraw0(from, to, amount)) => Withdraw(from, to, amount, e.lsn)
        }
        .toOption
        .filter {
          case Withdraw(from, _, _, _) => e.lsn.nodeId == from
          case Deposit(from, _, _, _) => e.lsn.nodeId == from
        }

    _.map(convert).unNone
  }

  /**
    * Causal ordering of AccountsEvents.
    * Ignores AccountsEvents that are invalid:
    *   - Deposited and withdrawn amounts do not match.
    *   - Balance is insufficent.
    */
  private def causalOrder[F[_]: Monad](
      accounts: Accounts[F]): Pipe[F, AccountsEvent, AccountsEvent] = {
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
    * Handles an AccountsEvent.
    */
  private def handleEvent[F[_]: Functor](
      daemon: GossipDaemon[F],
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F])(event: AccountsEvent)(implicit F: Effect[F]): F[Unit] =
    event match {
      case Withdraw(from, to, amount, lsn) =>
        val deposit = daemon.send[AccountsEvent](Deposit(from, to, amount, lsn))

        daemon.getNodeId.map(_ == to).ifM(deposit, F.unit)

      case Deposit(from, to, amount, _) =>
        // Transfer the money. Succeeds only if both succeeded.
        for {
          currentFrom <- kvs
            .get(from)
            .map(_.getOrElse(throw new IllegalArgumentException(s"No account for $from.")))

          currentTo <- kvs
            .get(to)
            .map(_.getOrElse(throw new IllegalArgumentException(s"No account for $to.")))

          _ <- kvs.put_*((from, tag[MoneyTag][Double](currentFrom - amount)),
                         (to, tag[MoneyTag][Double](currentTo + amount)))
        } yield ()
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
  * Can be seen as a partially-applied Withdraw
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
