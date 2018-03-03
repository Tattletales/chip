import Bla.{EOL, dispatcher}
import Named.AddUser
import cats.effect.IO
import io.circe.{Decoder, Json}
import simulacrum._

@typeclass trait HandleEvents[E] {
  def dispatch(name: String, payload: Json): IO[Unit]
}

object HandleEvents {
  implicit val base: HandleEvents[EOL] = new HandleEvents[EOL] {
    def dispatch(name: String, payload: Json): IO[Unit] = IO.unit
  }

  implicit def inductionStep[Event, Tail](
      implicit head: Named[Event],
      parser: Decoder[Event],
      replicate: Replicate[Event],
      tail: HandleEvents[Tail]): HandleEvents[(Event, Tail)] =
    new HandleEvents[(Event, Tail)] {
      def dispatch(name: String, payload: Json): IO[Unit] = {
        if (name == head.name)
          parser.decodeJson(payload).map(replicate.doStuff).getOrElse(IO.unit)
        else tail.dispatch(name, payload)
      }
    }
}

@typeclass trait Named[T] {
  val name: String
}

object Named {
  case class AddUser[User](user: User)

  implicit def namedAddUser[T]: Named[AddUser[T]] = new Named[AddUser[T]] {
    val name = "addUser"
  }
}

@typeclass trait Replicate[E] {
  def doStuff(event: E): IO[Unit]
}

object Bla {
  type EOL = Unit

  val dispatcher =
    implicitly[HandleEvents[(AddUser[Double], (Double, (String, EOL)))]]

  val out: Option[dispatcher.Out] = dispatcher.dispatch("addUser", ???)
}

object Events {
  sealed trait Tweet[E]
}
