package gossip

import cats.{FlatMap, Functor, Monad}
import cats.effect.Sync
import cats.implicits._
import events.Subscriber.Event
import events.{EventTypable, Subscriber}
import fs2.{Pipe, Pull, Segment, Stream}
import fs2.async.Ref
import fs2.async.mutable.Queue
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Encoder, Json}
import network.HttpClient
import org.http4s.{EntityDecoder, EntityEncoder}
import utils.StreamUtils.log

trait GossipDaemon[F[_]] {
  def getUniqueId: F[String]
  def send[Message: Encoder](m: Message)(implicit M: EventTypable[Message]): F[Unit]
  def subscribe: Stream[F, Event] // TODO should be val? no need to create new stream for every call
}

object GossipDaemon extends GossipDaemonInstances {
  def apply[F[_]](implicit D: GossipDaemon[F]): GossipDaemon[F] = D
}

sealed abstract class GossipDaemonInstances {
  implicit def localhost[F[_]: EntityDecoder[?[_], String]: EntityEncoder[?[_], Json]](
      httpClient: HttpClient[F],
      subscriber: Subscriber[F]): GossipDaemon[F] =
    new GossipDaemon[F] {
      private val root = "localhost:2018"

      def getUniqueId: F[String] = httpClient.get[String](s"$root/unique")

      def send[Message: Encoder](m: Message)(implicit M: EventTypable[Message]): F[Unit] =
        httpClient.unsafePostAndIgnore(s"$root/gossip/${M.eventType}", m.asJson)

      def subscribe: Stream[F, Event] = subscriber.subscribe(s"$root/events")
    }

  implicit def mock[F[_]: Monad: Sync](eventQueue: Queue[F, Event],
                                       counter: Ref[F, Int]): GossipDaemon[F] =
    new GossipDaemon[F] {

      def send[Message: Encoder](m: Message)(implicit M: EventTypable[Message]): F[Unit] =
        eventQueue.enqueue1(Event("YOLOSWAGDAB", M.eventType, m.asJson.spaces2))

      def getUniqueId: F[String] = counter.get.map(_.toString)
      //(counter.modify(_ + 1) >> counter.get).map(_.toString)

      def subscribe: Stream[F, Event] = eventQueue.dequeue.through(log("New event"))
    }

  implicit def causal[F[_]: FlatMap: EntityDecoder[?[_], String]: EntityEncoder[?[_], Json]](
      httpClient: HttpClient[F],
      subscriber: Subscriber[F],
      id: Ref[F, String]): GossipDaemon[F] =
    new GossipDaemon[F] {
      private case class CausalWrapper(payload: Json, dependsOn: String)

      private val root = "localhost:2018"

      override def subscribe: Stream[F, Event] =
        subscriber.subscribe(s"$root/events").through(causalOrder).through(updateId)

      override def getUniqueId: F[String] = httpClient.get[String](s"$root/unique")

      override def send[Message: Encoder](m: Message)(implicit M: EventTypable[Message]): F[Unit] =
        for {
          id <- id.get
          _ <- httpClient.unsafePostAndIgnore(s"$root/gossip/${M.eventType}",
                                              CausalWrapper(m.asJson, id).asJson)
        } yield ()

      /**
        * Updates the last seen id
        */
      private val updateId: Pipe[F, Event, Event] = _.evalMap(e => id.setSync(e.id).map(_ => e))

      /**
        * Makes sure that message are sent in the causal order.
        */
      private val causalOrder: Pipe[F, Event, Event] = {
        def go(s: Stream[F, Event], waiting: Map[String, Vector[Event]]): Pull[F, Event, Unit] =
          s.pull.uncons1.flatMap { // Option[event :: events]
            case Some((e, es)) =>
              decode[CausalWrapper](e.payload) // Original payload is wrapped
                .map { w =>
                  // Compute new Events that can be released and new waiting list.
                  val (release0, waiting0) =
                    release(
                      e.id,
                      waiting + (w.dependsOn -> (waiting(e.id) :+ Event(
                        e.id,
                        e.eventType,
                        e.payload)))) // Update waiting list. Append to keep order, maybe not necessary.

                  // Release events and then continue reading stream...
                  Pull.output(Segment.vector(release0)) >> go(es, waiting0)
                }
                .getOrElse(Pull.raiseError(
                  new IllegalStateException(s"Could not decode the payload ${e.payload}")))

            case None => Pull.done
          }

        go(_, Map.empty.withDefaultValue(Vector.empty)).stream
      }

      /**
        * Recursively releases events in waiting given the id.
        */
      private def release(
          id: String,
          waiting: Map[String, Vector[Event]]): (Vector[Event], Map[String, Vector[Event]]) = {
        val toUnlock = waiting(id) // Events that can be sent

        // Recursively try to unlock events that are now unlocked by the unlocked events
        // Preprend so they are sent in causal order.
        toUnlock.map(_.id).foldRight((toUnlock, waiting - id)) {
          case (id, (events, waiting)) =>
            val (release0, waiting0) = release(id, waiting)
            (release0 ++ events, waiting0)
        }
      }
    }
}
