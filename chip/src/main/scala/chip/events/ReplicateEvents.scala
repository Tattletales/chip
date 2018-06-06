package chip.events

import cats.effect.Effect
import cats.implicits._
import backend.events.{EventTypable, EventType, EventTyper, Payload}
import io.circe.Decoder
import shapeless.{::, HList, HNil}
import simulacrum._
import backend.storage.Database
import backend.gossip.Gossipable
import chip.events.ReplicateEvents.Event

/**
  * DSL for the replication of events to a [[Database]]
  */
trait ReplicateEvents[E] {
  def replicate[F[_]](db: Database[F])(event: Event)(implicit F: Effect[F]): F[Unit]
}

/**
  * Compile time inductive construction of a [[ReplicateEvents]] instance.
  * Source: https://youtu.be/Nm4OIhjjA2o
  */
object ReplicateEvents {
  implicit def baseCase: ReplicateEvents[HNil] = new ReplicateEvents[HNil] {
    def replicate[F[_]](db: Database[F])(event: Event)(implicit F: Effect[F]): F[Unit] = F.unit
  }

  implicit def inductionStep[E, Es <: HList](implicit head: EventTyper[E],
                                             decoder: Decoder[E],
                                             replicable: Replicable[E],
                                             tail: ReplicateEvents[Es]): ReplicateEvents[E :: Es] =
    new ReplicateEvents[E :: Es] {

      def replicate[F[_]](db: Database[F])(event: Event)(implicit F: Effect[F]): F[Unit] =
        if (event.eventType == head.eventType) {
          F.fromEither(event.payload.as[E])
            .flatMap(replicable.replicate(db))
        } else tail.replicate(db)(event)
    }

  final case class Event(eventType: EventType, payload: Payload)
}

/**
  * Typeclass providing the actions to run
  * in order to replicate each event `E`.
  */
@typeclass
trait Replicable[E] {
  def replicate[F[_]: Effect](db: Database[F]): E => F[Unit]
}
