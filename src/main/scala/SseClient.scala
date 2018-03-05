import SseClient.SSEvent
import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Sink, Source}
import cats.effect.{Async, Effect}
import fs2.Stream
import fs2.interop.reactivestreams._

import scala.concurrent.ExecutionContext

trait SseClient[F[_]] {
  def subscribe(uri: String): F[SSEvent]
}

object SseClient extends SseClientInstances {
  case class SSEvent(event: String, payload: String)

  def apply[F[_]](implicit S: SseClient[F]): SseClient[F] = S
}

sealed abstract class SseClientInstances {
  implicit def akka[F[_]: Effect]: SseClient[Stream[F, ?]] =
    new SseClient[Stream[F, ?]] {
      implicit val system: ActorSystem = ActorSystem()
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContext = system.dispatcher

      def subscribe(uri: String): Stream[F, SSEvent] =
        Stream.force(implicitly[Async[F]].async[Stream[F, SSEvent]] { cb =>
          (for {
            httpResponse <- Http().singleRequest(HttpRequest(uri = uri))

            akkaStream <- Unmarshal(httpResponse)
              .to[Source[ServerSentEvent, NotUsed]]

            fs2Stream = akkaStream
              .runWith(Sink.asPublisher[ServerSentEvent](fanout = false))
              .toStream[F]
              .flatMap(
                sse =>
                  sse.eventType match {
                    case Some(eventType) => Stream.emit(SSEvent(eventType, sse.data))
                    case None =>
                      Stream
                        .raiseError[SSEvent](
                          throw new NoSuchElementException("Missing event-type.")
                        )
                }
              )
          } yield fs2Stream).onComplete(t => cb(t.toEither))
        })
    }
}
