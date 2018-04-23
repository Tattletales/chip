package vault.events

import cats.{Functor, Monad, MonadError, Traverse}
import cats.effect.Effect
import cats.implicits._
import backend.events.EventTyper
import backend.events.Subscriber._
import backend.gossip.GossipDaemon
import backend.gossip.model.Node.NodeId
import shapeless.tag
import fs2.{Pipe, Pull, Sink, Stream}
import fs2.Stream.InvariantOps
import io.circe.generic.auto._
import io.circe.parser.{decode => circeDecode}
import vault.implicits._
import backend.implicits._
import backend.storage.KVStore
import cats.data.OptionT
import vault.model.Account.{Money, MoneyTag, User}
import vault.model._
import doobie.implicits._
import utils.StreamUtils
import vault.model.Accounts.AccountNotFound

/**
  * A transaction is split into two events Withdraw and Deposit so the events only relate
  * to the account of the sender.
  *
  * The Deposit event depends on the Withdraw through the latter's lsn.
  */
sealed trait AccountsEvent

case class Withdraw(from: User, to: User, amount: Money, lsn: Option[Lsn]) extends AccountsEvent
object Withdraw {

  /**
    * Set the lsn to None as it is not yet known at construction time.
    */
  def apply(from: User, to: User, amount: Money): Withdraw = Withdraw(from, to, amount, None)
}
case class Deposit(from: User, to: User, amount: Money, dependsOn: Lsn) extends AccountsEvent

sealed trait AccountsEventError extends Throwable
case class MissingLsnError(w: Withdraw) extends AccountsEventError {
  override def toString: String = s"Missing lsn in $w. It should have been added in the decoder."
}

object AccountsEvent {
  def handleAccountsEvents[F[_]: Effect](daemon: GossipDaemon[F],
                                         kvs: KVStore[F, User, Money],
                                         accounts: Accounts[F]): Sink[F, Event] =
    _.through(decodeAndCausalOrder(accounts))
      .through(StreamUtils.log("Handling"))
      .evalMap(handleEvent(daemon, kvs, accounts))

  def decodeAndCausalOrder[F[_]: Effect, O](accounts: Accounts[F]): Pipe[F, Event, AccountsEvent] =
    _.through(decode)
      .through(causalOrder(accounts))

  /**
    * Converts Events in AccountsEvents.
    * Ignores AccountsEvents with mismatching senders.
    */
  private def decode[F[_]](implicit F: MonadError[F, Throwable]): Pipe[F, Event, AccountsEvent] = {
    def convert(e: Event): F[AccountsEvent] = {
      val t = for {
        event <- circeDecode[AccountsEvent](e.payload).leftMap(err =>
          PayloadDecodingError(e.payload, err.getMessage))
        convertedEvent <- event match {
          case d @ Deposit(from, _, _, _) if e.lsn.nodeId == from =>
            Right[VaultError, AccountsEvent](d)
          case Withdraw(from, to, amount, _) if e.lsn.nodeId == from =>
            Right[AccountsEventError, AccountsEvent](Withdraw(from, to, amount, Some(e.lsn)))
          case e =>
            Left[AccountsEventError, AccountsEvent](SenderError(s"Wrong sender for event: $e"))
        }
      } yield convertedEvent

      F.fromEither(t)
    }

    _.evalMap(convert)
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
      Stream
        .InvariantOps(s)
        .pull
        .uncons1
        .flatMap {
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

              case w @ Withdraw(from, _, withdrawnAmount, Some(lsn)) =>
                val handleWithdraw = waitingFor.get(lsn) match {
                  case Some(deposit) =>
                    if (deposit.amount == withdrawnAmount) {
                      Pull.output1(w) >> Pull
                        .output1(deposit) >> go(es,
                                                accounts,
                                                waitingFor - lsn,
                                                withdrawn + (lsn -> withdrawnAmount))
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

              case Withdraw(_, _, _, None) => go(es, accounts, waitingFor, withdrawn)
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
      case Withdraw(from, to, amount, Some(lsn)) =>
        val deposit = daemon.send[AccountsEvent](Deposit(from, to, amount, lsn))

        daemon.getNodeId.map(_ == to).ifM(deposit, F.unit)

      case w @ Withdraw(_, _, _, None) => F.raiseError(MissingLsnError(w))

      case Deposit(from, to, amount, _) =>
        // Transfer the money. Succeeds only if both succeeded.
        for {
          currentFrom <- accounts.balance(from)

          currentTo <- accounts.balance(to)

          _ <- kvs.put_*((from, tag[MoneyTag][Double](currentFrom - amount)),
                         (to, tag[MoneyTag][Double](currentTo + amount)))
        } yield ()
    }

  /* ------ Errors ------ */
  sealed trait AccountsEventError extends Throwable

  case class PayloadDecodingError(payload: Payload, m: String) extends AccountsEventError {
    override def toString: String = s"Failed decoding the payload $payload.\n$m"
  }

  case class SenderError(m: String) extends AccountsEventError
}
