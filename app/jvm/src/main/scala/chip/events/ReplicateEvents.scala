package chip.events

import cats.effect.Effect
import cats.implicits._
import backend.events.EventTyper
import backend.events.Subscriber.{Event, EventType}
import io.circe.Decoder
import io.circe.parser.decode
import shapeless.{::, HList, HNil}
import simulacrum._
import backend.storage.Database

// https://youtu.be/Nm4OIhjjA2o
trait ReplicateEvents[E] {
  def replicate[F[_]](db: Database[F])(event: Event)(implicit F: Effect[F]): F[Unit]
}

object ReplicateEvents {
  implicit val baseCase: ReplicateEvents[HNil] = new ReplicateEvents[HNil] {
    def replicate[F[_]](db: Database[F])(event: Event)(implicit F: Effect[F]): F[Unit] = F.unit
  }

  implicit def inductionStep[E, Es <: HList](implicit head: EventTyper[E],
                                             decoder: Decoder[E],
                                             replicable: Replicable[E],
                                             tail: ReplicateEvents[Es]): ReplicateEvents[E :: Es] =
    new ReplicateEvents[E :: Es] {
      def replicate[F[_]](db: Database[F])(event: Event)(implicit F: Effect[F]): F[Unit] =
        if (event.eventType == head.eventType) {
          F.fromEither(decode[E](event.payload))
            .flatMap(replicable.replicate(db))
        } else tail.replicate(db)(event)

    }
}

@typeclass
trait Replicable[E] {
  def replicate[F[_]: Effect](db: Database[F]): E => F[Unit]
}
