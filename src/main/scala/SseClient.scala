import SseClient.Event
import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.unmarshalling.sse.EventStreamUnmarshalling._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import cats.effect.{Async, Effect, IO}
import fs2.Stream
import io.circe._
import streamz.converter._

import scala.concurrent.ExecutionContext

trait SseClient[F[_]] {
  def subscribe(uri: String): F[Event]
}

object SseClient extends SseClientInstances {
  case class Event(event: String, payload: String)

  def apply[F[_]](implicit S: SseClient[F]): SseClient[F] = S
}

sealed abstract class SseClientInstances {
  implicit def akka: SseClient[Stream[IO, ?]] =
    new SseClient[Stream[IO, ?]] {
      implicit val system: ActorSystem = ActorSystem()
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContext = system.dispatcher

      def subscribe(uri: String): Stream[IO, Event] =
        Stream
          .force(IO.fromFuture {
            IO(for {
              httpResponse <- Http().singleRequest(HttpRequest(uri = uri))

              akkaStream <- Unmarshal(httpResponse)
                .to[Source[ServerSentEvent, NotUsed]]

              fs2Stream = akkaStream
                .toStream()
                .flatMap(
                  sse =>
                    sse.eventType match {
                      case Some(eventType) => Stream.emit(Event(eventType, sse.data))
                      case None =>
                        Stream
                          .raiseError[Event](
                            throw new NoSuchElementException("Missing event-type.")
                          )
                  }
                )
            } yield fs2Stream)
          })
    }
}
