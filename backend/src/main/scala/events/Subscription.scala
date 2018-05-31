package backend.events

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{Uri => AkkaUri, _}
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.stream.ActorMaterializer
import akka.stream.alpakka.sse.scaladsl.EventSource
import akka.stream.scaladsl.Sink
import backend.errors.MalformedSSE
import backend.gossip.Node.NodeIdTag
import cats.data.OptionT
import cats.effect.Effect
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.Uri
import fs2.interop.reactivestreams._
import fs2.{Pipe, Stream}
import io.circe.Json
import io.circe.jawn._
import shapeless.tag

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

/**
  * Subscription DSL
  *
  * E is the type of the events that are delivered.
  */
trait Subscription[F[_], E] {

  /**
    * Subscribe to the event stream
    */
  def subscribe(uri: String Refined Uri): Stream[F, E]
}

object Subscription {
  /* ------ Interpreters ------ */

  /**
    * Interpreter to an `AkkaHTTP` server sent system
    */
  def serverSentEvent[F[_]](implicit F: Effect[F]): Subscription[F, SSEvent] =
    new Subscription[F, SSEvent] {
      implicit val system: ActorSystem = ActorSystem()
      implicit val materializer: ActorMaterializer = ActorMaterializer()
      implicit val executionContext: ExecutionContext = system.dispatcher

      /**
        * @see [[Subscription.subscribe]]
        *
        * Failures:
        *   - [[MalformedSSE]] TODO documentation
        */
      def subscribe(uri: String Refined Uri): Stream[F, SSEvent] = {
        val eventSource = EventSource(
          AkkaUri(uri.value),
          (a: HttpRequest) =>
            Http().singleRequest(a,
                                 settings =
                                   ConnectionPoolSettings(system).withIdleTimeout(Duration.Inf)),
          None)

        eventSource
          .runWith(Sink.asPublisher[ServerSentEvent](fanout = false))
          .toStream[F]
          .through(convert)

      }

      /**
        * Convert from [[ServerSentEvent]] to [[SSEvent]]
        *
        * Fails with [[MalformedSSE]] if there's [[ServerSentEvent]] that cannot be decoded.
        * For instance, it doesn't have an event type or the id is not to specs ("nodeId-eventId").
        */
      private val convert: Pipe[F, ServerSentEvent, SSEvent] =
        _.evalMap { sse =>
          val maybeEvent = for {
            eventType <- OptionT.fromOption[F](sse.eventType)
            id <- OptionT.fromOption[F](sse.id)
            Array(nodeId, eventId) = id.split("-")
            payload <- OptionT.liftF(F.fromEither(parse(sse.data)).map(tag[PayloadTag][Json]))
          } yield
            SSEvent(Lsn(tag[NodeIdTag][String](nodeId), tag[EventIdTag][Int](eventId.toInt)),
                    tag[EventTypeTag][String](eventType),
                    payload)

          maybeEvent.value.flatMap(F.fromOption(_, MalformedSSE(sse)))
        }
    }
}
