package vault.programs

import backend.gossip.GossipDaemon
import backend.storage.KVStore
import cats.Applicative
import cats.effect.Effect
import cats.implicits._
import fs2._
import shapeless.tag
import vault.events.Deposit
import vault.events.Transactions.handleTransactionStages
import vault.model.Account.{Money, MoneyTag, User}
import vault.model.Accounts

import scala.concurrent.ExecutionContext
import scala.util.Random

object Benchmark {

  /**
    * The head of `users` sends money to each user, one by one after the previous transaction has succeeded.
    */
  def loneSender[F[_]: Effect](users: Vector[User])(
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F],
      daemon: GossipDaemon[F])(implicit ec: ExecutionContext): Program[F] = new Program[F] {
    def run: Stream[F, Unit] = {
      val start = Stream.eval {
        daemon.getNodeId
          .map(_ == users.head)
          .ifM(accounts.transfer(users(1 % users.size), tag[MoneyTag][Double](0.001)),
               implicitly[Effect[F]].unit)

      }

      def next(deposit: Deposit): F[Unit] = deposit match {
        case Deposit(from, to, _, _) =>
          daemon.getNodeId
            .map(_ == from)
            .ifM({
              val toIndex = users.indexOf(to)
              accounts.transfer(users((toIndex + 1) % users.size), tag[MoneyTag][Double](0.001))
            }, implicitly[Effect[F]].unit)
      }

      val handler = daemon.subscribe.through(handleTransactionStages(next)(daemon, kvs, accounts))

      Stream(start, handler).join(2).drain
    }
  }

  /**
    * Infinitely transfer money to the other users. The order is random but each
    * user will receive transactions one after each other.
    */
  def random[F[_]: Effect](users: List[User])(
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F],
      daemon: GossipDaemon[F])(implicit ec: ExecutionContext): Program[F] = new Program[F] {
    def run: Stream[F, Unit] = {
      val benchmark = for {
        me <- Stream.eval(daemon.getNodeId)
        shuffledUsers <- Stream(Random.shuffle(users.filter(_ != me))).covary[F]
        _ <- Stream.repeatEval {
          shuffledUsers
            .traverse(user => accounts.transfer(user, tag[MoneyTag][Double](0.001)))
            .map(_ => ())
        }
      } yield ()

      val handler = daemon.subscribe.through(
        handleTransactionStages(_ => implicitly[Applicative[F]].unit)(daemon, kvs, accounts))

      Stream(benchmark, handler).join(2).drain
    }
  }

  /**
    * Infinitely transfer at the user after itself in the list.
    * When the transaction succeeds, transfer money to the next user, and so on...
    */
  def localRoundRobin[F[_]: Effect](users: Vector[User])(
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F],
      daemon: GossipDaemon[F])(implicit ec: ExecutionContext): Program[F] = new Program[F] {
    def run: Stream[F, Unit] = {
      val start = Stream.eval(for {
        me <- daemon.getNodeId
        first = (users.indexOf(me) + 1) % users.size
        _ <- accounts.transfer(users(first), tag[MoneyTag][Double](0.001))
      } yield ())

      def next(deposit: Deposit): F[Unit] = deposit match {
        case Deposit(from, to, _, _) =>
          daemon.getNodeId
            .map(_ == from)
            .ifM({
              val toIndex = users.indexOf(to)
              accounts.transfer(users((toIndex + 1) % users.size), tag[MoneyTag][Double](0.001))
            }, implicitly[Effect[F]].unit)
      }

      val handler = daemon.subscribe.through(handleTransactionStages(next)(daemon, kvs, accounts))

      Stream(start, handler).join(2).drain
    }
  }

  /**
    * Infinitely transfer money from one user to the other.
    */
  def roundRobin[F[_]: Effect](users: Vector[User])(
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F],
      daemon: GossipDaemon[F])(implicit ec: ExecutionContext): Program[F] = new Program[F] {
    def run: Stream[F, Unit] = {
      val start = Stream.eval {
        daemon.getNodeId
          .map(_ == users.head)
          .ifM(accounts.transfer(users(1 % users.size), tag[MoneyTag][Double](0.001)),
               implicitly[Effect[F]].unit)
      }

      def next(deposit: Deposit): F[Unit] = deposit match {
        case Deposit(_, to, _, _) =>
          daemon.getNodeId
            .map(_ == to)
            .ifM({
              val toIndex = users.indexOf(to)
              accounts.transfer(users((toIndex + 1) % users.size), tag[MoneyTag][Double](0.001))
            }, implicitly[Effect[F]].unit)
      }

      val handler = daemon.subscribe.through(handleTransactionStages(next)(daemon, kvs, accounts))

      Stream(start, handler).join(2).drain
    }
  }
}
