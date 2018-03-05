import HandleEvents.Event
import TweetActions.TweetsAction
import UsersActions.UsersAction
import cats.Applicative
import io.circe.fs2._
import io.circe.{Decoder, Json}
import fs2._
import shapeless.{::, HList, HNil}
import simulacrum._

// https://youtu.be/Nm4OIhjjA2o
trait HandleEvents[E] {
  def handle[F[_]](r: Repo[F])(event: Event)(implicit F: Applicative[F]): Stream[F, Unit]
}

object HandleEvents {
  case class Event(name: String, payload: Json)

  implicit val baseCase: HandleEvents[HNil] = new HandleEvents[HNil] {
    def handle[F[_]](r: Repo[F])(event: Event)(implicit F: Applicative[F]): Stream[F, Unit] =
      Stream.eval(F.pure(()))
  }

  implicit def inductionStep[E, Es <: HList](implicit head: Named[E],
                                             parser: Decoder[E],
                                             replicate: Replicable[E],
                                             tail: HandleEvents[Es]): HandleEvents[E :: Es] =
    new HandleEvents[E :: Es] {
      def handle[F[_]](r: Repo[F])(event: Event)(implicit F: Applicative[F]): Stream[F, Unit] = {
        if (event.name == head.name)
          Stream
            .emit(event.payload)
            .covary[F]
            .through(decoder[F, E])
            .to(replicate.replicate(r))
        else tail.handle(r)(event)
      }
    }
}

@typeclass trait Named[T] {
  def name: String
}

object Named {
  case class AddUser[User](user: User)

  implicit def namedAddUser[T]: Named[AddUser[T]] = new Named[AddUser[T]] {
    val name = "addUser"
  }
}

@typeclass trait Replicable[E] {
  def replicate[F[_]](r: Repo[F]): Sink[F, E]
}
