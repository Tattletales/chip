package vault.benchmarks

import backend.gossip.GossipDaemon
import backend.storage.KVStore
import cats.effect.Effect
import cats.implicits._
import eu.timepit.refined.api.RefType.applyRefM
import fs2._
import backend.gossip.Gossipable
import cats.data.NonEmptyList
import backend.programs.Program
import vault.events.{Deposit, EventsHandler, TransactionStage}
import vault.model.{Money, User}
import vault.model.Accounts

import scala.util.{Random => ScalaRandom}
import scala.concurrent.ExecutionContext.Implicits.global

sealed trait Benchmark
case object LoneSender extends Benchmark
case object Random extends Benchmark
case object LocalRoundRobin extends Benchmark
case object RoundRobin extends Benchmark

object Benchmark {
  def apply[F[_]: Effect, E: Gossipable](benchmark: Benchmark)(users: NonEmptyList[User])(
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F],
      daemon: GossipDaemon[F, TransactionStage, E]): Program[F, Unit] =
    benchmark match {
      case LoneSender      => loneSender(users.toList)(kvs, accounts, daemon)
      case Random          => random(users.toList)(kvs, accounts, daemon)
      case LocalRoundRobin => localRoundRobin(users.toList)(kvs, accounts, daemon)
      case RoundRobin      => roundRobin(users.toList)(kvs, accounts, daemon)
    }

  /**
    * The head of `users` sends money to each user, one by one after the previous transaction has succeeded.
    */
  private def loneSender[F[_]: Effect, E: Gossipable](users: List[User])(
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F],
      daemon: GossipDaemon[F, TransactionStage, E]): Program[F, Unit] =
    new Program[F, Unit] {
      def run: Stream[F, Unit] = {
        val start = Stream.eval {
          daemon.getNodeId
            .map(_ == users.head)
            .ifM(accounts.transfer(users(1 % users.size), applyRefM[Money](0.001)),
                 implicitly[Effect[F]].unit)
        }

        def next(deposit: Deposit): F[Unit] = deposit match {
          case Deposit(from, to, _, _) =>
            daemon.getNodeId
              .map(_ == from)
              .ifM({
                val toIndex = users.indexOf(to)
                accounts.transfer(users((toIndex + 1) % users.size), applyRefM[Money](0.001))
              }, implicitly[Effect[F]].unit)
        }

        val handler = EventsHandler.withNext(daemon, kvs, accounts)(next).run

        Stream(start, handler).join(2).drain
      }
    }

  /**
    * Infinitely transfer money to the other users.
    */
  private def random[F[_]: Effect, E: Gossipable](users: List[User])(
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F],
      daemon: GossipDaemon[F, TransactionStage, E]): Program[F, Unit] =
    new Program[F, Unit] {
      def run: Stream[F, Unit] = {
        val benchmark = for {
          me <- Stream.eval(daemon.getNodeId)
          shuffledUsers <- Stream(ScalaRandom.shuffle(users.filter(_ != me))).covary[F]
          _ <- Stream.repeatEval {
            shuffledUsers
              .traverse(user => accounts.transfer(user, applyRefM[Money](0.001)))
              .map(_ => ())
          }
        } yield ()

        val handler = EventsHandler(daemon, kvs, accounts).run

        Stream(benchmark, handler).join(2).drain
      }
    }

  /**
    * Infinitely transfer at the user after itself in the list.
    * When the transaction succeeds, transfer money to the next user, and so on...
    */
  private def localRoundRobin[F[_]: Effect, E: Gossipable](users: List[User])(
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F],
      daemon: GossipDaemon[F, TransactionStage, E]): Program[F, Unit] =
    new Program[F, Unit] {
      def run: Stream[F, Unit] = {
        val start = Stream.eval(for {
          me <- daemon.getNodeId
          first = (users.indexOf(me) + 1) % users.size
          _ <- accounts.transfer(users(first), applyRefM[Money](0.001))
        } yield ())

        def next(deposit: Deposit): F[Unit] = deposit match {
          case Deposit(from, to, _, _) =>
            daemon.getNodeId
              .map(_ == from)
              .ifM({
                val toIndex = users.indexOf(to)
                accounts.transfer(users((toIndex + 1) % users.size), applyRefM[Money](0.001))
              }, implicitly[Effect[F]].unit)
        }

        val handler = EventsHandler.withNext(daemon, kvs, accounts)(next).run

        Stream(start, handler).join(2).drain
      }
    }

  /**
    * Infinitely transfer money from one user to the other.
    */
  private def roundRobin[F[_]: Effect, E: Gossipable](users: List[User])(
      kvs: KVStore[F, User, Money],
      accounts: Accounts[F],
      daemon: GossipDaemon[F, TransactionStage, E]): Program[F, Unit] =
    new Program[F, Unit] {
      def run: Stream[F, Unit] = {
        val start = Stream.eval {
          daemon.getNodeId
            .map(_ == users.head)
            .ifM(accounts.transfer(users(1 % users.size), applyRefM[Money](0.001)),
                 implicitly[Effect[F]].unit)
        }

        def next(deposit: Deposit): F[Unit] = deposit match {
          case Deposit(_, to, _, _) =>
            daemon.getNodeId
              .map(_ == to)
              .ifM({
                val toIndex = users.indexOf(to)
                accounts.transfer(users((toIndex + 1) % users.size), applyRefM[Money](0.001))
              }, implicitly[Effect[F]].unit)
        }

        val handler = EventsHandler.withNext(daemon, kvs, accounts)(next).run

        Stream(start, handler).join(2).drain
      }
    }
}
