import HandleEvents.Event
import cats.effect.Effect
import cats.implicits._
import io.circe.{Decoder, Json}
import shapeless.{::, HList, HNil}
import simulacrum._

// https://youtu.be/Nm4OIhjjA2o
trait HandleEvents[E] {
  def handle[F[_]](db: Database[F])(event: Event)(implicit F: Effect[F]): F[Unit]
}

object HandleEvents {
  case class Event(name: String, payload: Json)

  implicit val baseCase: HandleEvents[HNil] = new HandleEvents[HNil] {
    def handle[F[_]](db: Database[F])(event: Event)(implicit F: Effect[F]): F[Unit] = F.pure(())
  }

  implicit def inductionStep[E, Es <: HList](implicit head: Named[E],
                                             decoder: Decoder[E],
                                             replicable: Replicable[E],
                                             tail: HandleEvents[Es]): HandleEvents[E :: Es] =
    new HandleEvents[E :: Es] {
      def handle[F[_]](db: Database[F])(event: Event)(implicit F: Effect[F]): F[Unit] =
        if (event.name == head.name)
          F.fromEither(event.payload.as[E]).flatMap(replicable.replicate(db))
        else tail.handle(db)(event)
    }
}

@typeclass
trait Named[T] {
  def name: String
}

@typeclass
trait Replicable[E] {
  def replicate[F[_]: Effect](db: Database[F]): E => F[Unit]
}
