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
import monix.eval.Task
import streamz.converter._

import scala.concurrent.ExecutionContext

trait Subscribtion[F[_], Event] {
  def subscribe(uri: String): F[Stream[IO, Event]]
}

object Subscribtion extends SubscribtionInstances {
  def apply[F[_], Event](
      implicit S: Subscribtion[F, Event]): Subscribtion[F, Event] = S
}

sealed abstract class SubscribtionInstances {
  implicit def akkaSubscribtion[Event](
      decoder: String => Event): Subscribtion[Task, Event] =
    new Subscribtion[Task, Event] {
      implicit val system: ActorSystem = ActorSystem()
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContext = system.dispatcher

      def subscribe(uri: String): Task[Stream[IO, Event]] = Task.defer {
        val future = Http()
          .singleRequest(HttpRequest(uri = uri))
          .flatMap(Unmarshal(_).to[Source[ServerSentEvent, NotUsed]])
          .map(_.toStream().map(e => decoder(e.data)))
        Task.fromFuture(future)
      }
    }
}
