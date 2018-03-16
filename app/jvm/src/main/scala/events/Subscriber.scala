package events

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
import events.Subscriber.Event
import fs2.Stream
import fs2.interop.reactivestreams._

import scala.concurrent.ExecutionContext

trait Subscriber[F[_]] {
  def subscribe(uri: String): Stream[F, Event]
}

object Subscriber extends SubscriberInstances {
  case class Event(eventType: String, payload: String)

  def apply[F[_]](implicit S: Subscriber[F]): Subscriber[F] = S
}

sealed abstract class SubscriberInstances {
  implicit def serverSentEvent[F[_]: Effect]: Subscriber[F] =
    new Subscriber[F] {
      implicit val system: ActorSystem = ActorSystem()
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContext = system.dispatcher

      def subscribe(uri: String): Stream[F, Event] =
        Stream.force(implicitly[Async[F]].async[Stream[F, Event]] { cb =>
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
                    case Some(eventType) => Stream.emit(Event(eventType, sse.data))
                    case None =>
                      Stream
                        .raiseError[Event](new NoSuchElementException(
                          s"Missing event-type for payload ${sse.data}"))
                }
              )
          } yield fs2Stream).onComplete(t => cb(t.toEither))
        })
    }
}
