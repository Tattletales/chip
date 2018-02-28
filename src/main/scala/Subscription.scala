import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import cats.effect.IO
import fs2.Stream
import streamz.converter._

import scala.concurrent.ExecutionContext

trait Subscription[F[_], Event] {
  def subscribe(uri: String): F[Stream[IO, Event]]
}

object Subscription extends SubscriptionInstances {
  def apply[F[_], Event](
      implicit S: Subscription[F, Event]): Subscription[F, Event] = S
}

sealed abstract class SubscriptionInstances {
  implicit def akkaSubscription[Event](
      decoder: String => Event): Subscription[IO, Event] =
    new Subscription[IO, Event] {
      implicit val system: ActorSystem = ActorSystem()
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContext = system.dispatcher

      def subscribe(uri: String): IO[Stream[IO, Event]] = IO.fromFuture {
        IO(
          Http()
            .singleRequest(HttpRequest(uri = uri))
            .flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])
            .map(_.toStream().map(e => decoder(e.data))))
      }
    }
}
