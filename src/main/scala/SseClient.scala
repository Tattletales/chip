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

trait SseClient[F[_], Uri, Event] {
  def subscribe(uri: Uri): F[Stream[IO, Event]]
}

object SseClient extends SseClientInstances {
  def apply[F[_], Uri, Event](
      implicit S: SseClient[F, Uri, Event]): SseClient[F, Uri, Event] =
    S
}

sealed abstract class SseClientInstances {
  implicit def akka[Event](
      decoder: String => Event): SseClient[IO, String, Event] =
    new SseClient[IO, String, Event] {
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
