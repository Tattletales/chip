package vault.events

import cats.{Applicative, Functor, Monad, MonadError}
import cats.effect.Effect
import cats.implicits._
import cats.syntax.parallel._
import cats.instances.list._
import backend.events.EventTyper
import backend.events.Subscriber._
import backend.gossip.GossipDaemon
import backend.gossip.Node.NodeId
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
import gossip.Gossipable
import gossip.Gossipable.ops._
import utils.StreamUtils
import vault.errors.{MissingLsnError, PayloadDecodingError, SenderError}

/**
  * A transaction is split into two stages: [[Withdraw]] and [[Deposit]].
  *
  * The [[Deposit]] event depends on the [[Withdraw]] through the latter's [[Withdraw.lsn]] - [[Deposit.dependsOn]].
  */
sealed trait TransactionStage

/**
  * Initial stage of a transaction.
  */
case class Withdraw(from: User, to: User, amount: Money, lsn: Option[Lsn]) extends TransactionStage
object Withdraw {

  /**
    * Set the lsn to None as it is not yet known at construction time.
    * It will only become available when the [[GossipDaemon]] delivers the event with a lsn.
    */
  def apply(from: User, to: User, amount: Money): Withdraw = Withdraw(from, to, amount, None)
}

/**
  * Second and final stage of a transaction. When this stage is processed the transaction is attempted.
  */
case class Deposit(from: User, to: User, amount: Money, dependsOn: Lsn) extends TransactionStage

object Transactions {

  /**
    * Handle the events.
    *
    * The `next` function can be used to specify what to do after the
    * transaction has succeeded.
    *
    * Decodes and reorders the transaction stages so they are delivered in a causal order.
    * Finally, they are processed by the transaction handler.
    */
  def handleTransactionStages[F[_]: Effect, E: Gossipable](next: Deposit => F[Unit])(
      daemon: GossipDaemon[F, E],
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F]): Sink[F, E] =
    _.through(decodeAndCausalOrder(accounts))
      .through(StreamUtils.log("Delivered"))
      .through(StreamUtils.logToFile("DELIVERED", "test"))
      .evalMap(processTransactionStage(daemon, kvs, accounts)(next))

  /**
    * Decode and reorder the transaction stages so they are delivered in a causal order.
    */
  def decodeAndCausalOrder[F[_]: Effect, E: Gossipable](accounts: Accounts[F]): Pipe[F, E, TransactionStage] =
    _.through(decode).through(causalOrder(accounts))

  /**
    * Convert [[WSEvent]] into a [[TransactionStage]].
    *
    * Failures:
    *   - [[PayloadDecodingError]] if the [[WSEvent.payload]] cannot be decoded.
    *   - [[SenderError]] if there is a mismatch in the sender of event and the actual sender.
    */
  private def decode[F[_], E: Gossipable](
      implicit F: MonadError[F, Throwable]): Pipe[F, E, TransactionStage] = {
    def convert(e: E): F[TransactionStage] =
      for {
        event <- F.fromEither(circeDecode[TransactionStage](e.payload)).adaptError {
          case _ => PayloadDecodingError(e.payload)
        }

        convertedEvent <- event match {
          case d @ Deposit(_, to, _, _) if e.lsn.nodeId == to => F.pure(d)
          case Withdraw(from, to, amount, _) if e.lsn.nodeId == from =>
            F.pure(Withdraw(from, to, amount, Some(e.lsn)))
          case err =>
            println(s"event = $event e = $err lsn = ${e.lsn}")
            F.raiseError(SenderError(s"Wrong sender for event: $err"))
        }
      } yield convertedEvent

    _.evalMap(convert)

  }

  // TODO better documentation
  /**
    * Causal ordering of [[TransactionStage]]s.
    *
    * Ignores AccountsEvents that are invalid:
    *   - Deposited and withdrawn amounts do not match.
    *   - Balance is insufficent.
    */
  private def causalOrder[F[_]: Monad](
      accounts: Accounts[F]): Pipe[F, TransactionStage, TransactionStage] = {
    def go(s: Stream[F, TransactionStage],
           accounts: Accounts[F],
           waitingFor: Map[Lsn, Deposit],
           withdrawn: Map[Lsn, Money]): Pull[F, TransactionStage, Unit] =
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
                    if (depositedAmount == withdrawnAmount)
                      Pull.output1(d) >> go(es, accounts, waitingFor, withdrawn - dependsOn)
                    else
                      go(es, accounts, waitingFor, withdrawn) // Ignore, transaction is faulty.
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
                    if (deposit.amount == withdrawnAmount)
                      Pull.output1(w) >> Pull
                        .output1(deposit) >> go(es,
                                                accounts,
                                                waitingFor - lsn,
                                                withdrawn + (lsn -> withdrawnAmount))
                    else
                      // Ignore, transaction is faulty.
                      go(es, accounts, waitingFor - lsn, withdrawn)
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
    * Processes a [[TransactionStage]].
    *
    * The `next` function can be used to specify what to do after the
    * transaction has succeeded.
    *
    * Withdraw:
    *   Gossip a Deposit if the transfer is for the current user.
    *
    * Deposit:
    *   Do the actual transfer of money.
    *   Succeeds if and only if both the debit and credit succeed.
    *
    * Fails with [[MissingLsnError]] if there is a [[Withdraw]] with [[Withdraw.lsn]] empty.
    */
  private def processTransactionStage[F[_], E](daemon: GossipDaemon[F, E],
                                               kvs: KVStore[F, User, Money],
                                               accounts: Accounts[F])(next: Deposit => F[Unit])(
      event: TransactionStage)(implicit F: MonadError[F, Throwable]): F[Unit] = {

    /**
      *
      */
    def transfer(from: User, to: User, amount: Money): F[Unit] =
      (accounts.balance(from), accounts.balance(to)).mapN {
        case (currentFrom, currentTo) =>
          kvs.put_*((from, tag[MoneyTag][Double](currentFrom - amount)),
                    (to, tag[MoneyTag][Double](currentTo + amount)))
      }.flatten

    event match {
      case Withdraw(from, to, amount, Some(lsn)) =>
        val deposit = daemon.send[TransactionStage](Deposit(from, to, amount, lsn))

        daemon.getNodeId.map(_ == to).ifM(deposit, F.unit)

      case w @ Withdraw(_, _, _, None) => F.raiseError(MissingLsnError(w))

      case d @ Deposit(from, to, amount, _) =>
        transfer(from, to, amount) >> next(d)
    }
  }
}
