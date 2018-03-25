package vault.events

import cats.Parallel
import cats.effect.Effect
import cats.implicits._
import backend.events.EventTyper
import backend.events.Subscriber.Event
import backend.gossip.GossipDaemon
import backend.gossip.model.Node.NodeId
import io.circe.Decoder
import io.circe.parser.decode
import shapeless.{::, HList, HNil}
import simulacrum._
import backend.storage.Database
import vault.model.Accounts

// https://youtu.be/Nm4OIhjjA2o
trait ReplicateEvents[E] {
  def replicate[F[_]](db: Database[F], daemon: GossipDaemon[F], accounts: Accounts[F])(
      event: Event)(implicit F: Effect[F]): F[Unit]
}

object ReplicateEvents {
  implicit val baseCase: ReplicateEvents[HNil] = new ReplicateEvents[HNil] {
    def replicate[F[_]](db: Database[F], daemon: GossipDaemon[F], accounts: Accounts[F])(
        event: Event)(implicit F: Effect[F]): F[Unit] =
      F.unit
  }

  implicit def inductionStep[E, Es <: HList](implicit head: EventTyper[E],
                                             trace: Trace[E],
                                             decoder: Decoder[E],
                                             replicable: Replicator[E],
                                             tail: ReplicateEvents[Es]): ReplicateEvents[E :: Es] =
    new ReplicateEvents[E :: Es] {
      def replicate[F[_]](db: Database[F], daemon: GossipDaemon[F], accounts: Accounts[F])(
          event: Event)(implicit F: Effect[F]): F[Unit] =
        if (event.eventType == head.eventType) {
          for {
            action <- F.fromEither(decode[E](event.payload))
            _ <- F.whenA(event.lsn.nodeId == trace.sender(action))(
              replicable.replicate(event.lsn.nodeId, db, daemon, accounts)(action))
          } yield ()
        } else tail.replicate(db, daemon, accounts)(event)

    }
}

@typeclass
trait Replicator[E] {
  def replicate[F[_]](sender: NodeId,
                      db: Database[F],
                      daemon: GossipDaemon[F],
                      accounts: Accounts[F])(e: E)(implicit F: Effect[F]): F[Unit]
}

@typeclass
trait Trace[E] {
  def sender(e: E): NodeId
}
