package vault

import backend.events.Subscriber.{Event, EventId}
import cats.effect.{Effect, IO}
import doobie.util.transactor.Transactor
import fs2.StreamApp.ExitCode
import fs2._
import backend.gossip.GossipDaemon
import backend.gossip.model.Node._
import backend.storage.{Database, KVStore}
import vault.model.Account.User
import shapeless.tag
import org.http4s.circe._
import vault.events.AccountsEvent
import vault.events.AccountsEvent.handleAccountsEvents
import vault.implicits._
import backend.implicits._
import vault.model.Account.Money
import vault.model.Accounts

import scala.concurrent.ExecutionContext.Implicits.global

object VaultApp extends Vault[IO]

class Vault[F[_]: Effect] extends StreamApp[F] {
  def stream(args: List[String], requestShutdown: F[Unit]): Stream[F, ExitCode] =
    Scheduler(corePoolSize = 10).flatMap { implicit S =>
      for {
        eventQueue <- Stream.eval(async.unboundedQueue[F, Event])

        db: Database[F] = Database.doobieDatabase[F](xa)
        kvs: KVStore[F, User, Money] = KVStore.dbKVS[F, User, Money](db)

        daemon = GossipDaemon.mock[F](eventQueue)

        accounts = Accounts.simple[F](daemon, kvs)

        handler = daemon.subscribe.through(handleAccountsEvents(daemon, kvs, accounts))

        // TODO add server
        ec <- Stream(handler, ???).join(2).drain ++ Stream.emit(ExitCode.Success)
      } yield ec
    }

  val xa: Transactor[F] = Transactor.fromDriverManager[F](
    "org.postgresql.Driver", // driver classname
    "jdbc:postgresql:chip_db", // connect URL (driver-specific)
    "florian", // user
    "mJ9da5mPHniKrsr8KeYx" // password
  )
}
