package events

import cats.effect.Effect
import cats.implicits._
import events.Subscriber.{Event, EventId, EventType, NodeId}
import fs2.async.Ref
import io.circe.Decoder
import io.circe.parser.decode
import shapeless.{::, HList, HNil}
import simulacrum._
import storage.Database

// https://youtu.be/Nm4OIhjjA2o
trait ReplicateEvents[E] {
  def replicate[F[_]](vClock: Ref[F, Map[NodeId, EventId]], db: Database[F])(event: Event)(
      implicit F: Effect[F]): F[Unit]
}

object ReplicateEvents {
  implicit val baseCase: ReplicateEvents[HNil] = new ReplicateEvents[HNil] {
    def replicate[F[_]](vClock: Ref[F, Map[NodeId, EventId]], db: Database[F])(event: Event)(
        implicit F: Effect[F]): F[Unit] = F.pure(())
  }

  implicit def inductionStep[E, Es <: HList](implicit head: EventTypable[E],
                                             decoder: Decoder[E],
                                             replicable: Replicable[E],
                                             tail: ReplicateEvents[Es]): ReplicateEvents[E :: Es] =
    new ReplicateEvents[E :: Es] {
      def replicate[F[_]](vClock: Ref[F, Map[NodeId, EventId]], db: Database[F])(event: Event)(
          implicit F: Effect[F]): F[Unit] =
        for {
          _ <- if (event.eventType == head.eventType) {
            F.fromEither(decode[E](event.payload))
              .flatMap(replicable.replicate(db))
          } else tail.replicate(vClock, db)(event)
          v <- vClock.get
          _ <- vClock.setSync(v + (event.lsn.nodeId -> event.lsn.eventId))
        } yield ()
    }
}

@typeclass
trait EventTypable[T] {
  def eventType: EventType
}

@typeclass
trait Replicable[E] {
  def replicate[F[_]: Effect](db: Database[F]): E => F[Unit]
}
