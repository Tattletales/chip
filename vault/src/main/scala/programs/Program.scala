package vault.programs

import fs2._
import cats.implicits._
import backend.gossip.GossipDaemon
import backend.storage.KVStore
import cats.Applicative
import cats.effect.Effect
import shapeless.tag
import utils.StreamUtils.interruptAfter
import vault.events.Deposit
import vault.events.Transactions.handleTransactionStages
import vault.model.Account.{Money, MoneyTag, User}
import vault.model.Accounts

import scala.concurrent.ExecutionContext
import scala.util.Random
import scala.concurrent.duration._

/**
  * Program DSL
  */
trait Program[F[_]] {

  /**
    * Convert a program to a [[Stream]]
    */
  def run: Stream[F, Unit]

  /**
    * Run the program for the given `duration`
    */
  def runFor(duration: FiniteDuration)(implicit F: Effect[F],
                                       ec: ExecutionContext): Stream[F, Unit] =
    run.through(interruptAfter(duration))
}