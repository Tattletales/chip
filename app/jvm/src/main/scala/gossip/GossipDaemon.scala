package gossip

import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, Monad}
import events.Subscriber._
import events.Subscriber.implicits._
import events.{EventTypable, Subscriber}
import fs2.async.Ref
import fs2.async.mutable.Queue
import fs2.{Pipe, Pull, Segment, Stream}
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Encoder, Json}
import network.HttpClient
import network.HttpClient.UriTag
import org.http4s.{EntityDecoder, EntityEncoder}
import utils.StreamUtils.log
import shapeless.tag

trait GossipDaemon[F[_]] {
  def getUniqueId: F[NodeId]
  def send[Message: Encoder](m: Message)(implicit M: EventTypable[Message]): F[Unit]
  def subscribe
    : Stream[F, Event] // TODO should be val? no need to create new stream for every call
  def getLog(lsn: Lsn): F[List[Event]]
}

object GossipDaemon extends GossipDaemonInstances {
  def apply[F[_]](implicit D: GossipDaemon[F]): GossipDaemon[F] = D
}

sealed abstract class GossipDaemonInstances {
  implicit def localhost[F[_]: EntityDecoder[?[_], String]: EntityEncoder[?[_], Json]](
      httpClient: HttpClient[F],
      subscriber: Subscriber[F]): GossipDaemon[F] =
    new GossipDaemon[F] {
      private val root = "localhost:59234"

      def getUniqueId: F[NodeId] = httpClient.get[NodeId](tag[UriTag][String](s"$root/unique"))

      def send[Message: Encoder](m: Message)(implicit M: EventTypable[Message]): F[Unit] =
        httpClient.unsafePostAndIgnore(tag[UriTag][String](s"$root/gossip/${M.eventType}"),
                                       m.asJson)

      def subscribe: Stream[F, Event] = subscriber.subscribe(s"$root/events")

      def getLog(lsn: Lsn): F[List[Event]] = ???
    }

  implicit def mock[F[_]: Monad: Sync](eventQueue: Queue[F, Event],
                                       counter: Ref[F, NodeId]): GossipDaemon[F] =
    new GossipDaemon[F] {

      def send[Message: Encoder](m: Message)(implicit M: EventTypable[Message]): F[Unit] =
        eventQueue.enqueue1(
          Event(Lsn(tag[NodeIdTag][String]("Foo"), tag[EventIdTag][Int](123)),
                M.eventType,
                tag[PayloadTag][String](m.asJson.noSpaces)))

      def getUniqueId: F[NodeId] = counter.get
      //(counter.modify(_ + 1) >> counter.get).map(_.toString)

      def subscribe: Stream[F, Event] = eventQueue.dequeue.through(log("New event"))

      def getLog(lsn: Lsn): F[List[Event]] = ???
    }

  implicit def causal[F[_]: Monad: EntityDecoder[?[_], String]: EntityEncoder[?[_], Json]](
      httpClient: HttpClient[F],
      subscriber: Subscriber[F],
      vClock: Ref[F, Map[NodeId, EventId]]): GossipDaemon[F] =
    new GossipDaemon[F] {
      private case class CausalWrapper(payload: Payload, dependsOn: Lsn)

      private val root = "localhost:2018"

      def subscribe: Stream[F, Event] =
        subscriber
          .subscribe(s"$root/events")
          .through(causalOrder)

      def getUniqueId: F[NodeId] = httpClient.get[NodeId](tag[UriTag][String](s"$root/unique"))

      def send[Message: Encoder](m: Message)(implicit M: EventTypable[Message]): F[Unit] =
        for {
          id <- getUniqueId
          vClock <- vClock.get
          _ <- httpClient.unsafePostAndIgnore(
            tag[UriTag][String](s"$root/gossip/${M.eventType}"),
            CausalWrapper(tag[PayloadTag][String](m.asJson.noSpaces), Lsn(id, vClock(id))).asJson)
        } yield ()

      def getLog(lsn: Lsn): F[List[Event]] = ???

      /**
        * Makes sure that message are sent in the causal order.
        */
      private val causalOrder: Pipe[F, Event, Event] = {
        def go(s: Stream[F, Event], waitingFor: Set[Lsn])(
            implicit F: Applicative[F]): Pull[F, Event, Unit] =
          s.pull.uncons1.flatMap {
            case Some((e, es)) =>
              decode[CausalWrapper](e.payload)
                .map { w =>
                  for {
                    vClock <- Pull.eval(vClock.get)
                    pull <- if (vClock(w.dependsOn.nodeId) >= w.dependsOn.eventId) {
                      if (waitingFor(e.lsn)) {
                        release(e.lsn).flatMap { events =>
                          Pull.output(Segment.seq(e.copy(payload = w.payload) +: events)) >> go(
                            es,
                            waitingFor - e.lsn)
                        }
                      } else {
                        Pull.output1(e.copy(payload = w.payload)) >> go(es, waitingFor)
                      }
                    } else {
                      go(es, waitingFor + w.dependsOn)
                    }
                  } yield pull
                }
                .getOrElse(Pull.raiseError(
                  new IllegalStateException(s"Could not decode the payload ${e.payload}")))

            case None => Pull.done
          }

        go(_, Set.empty).stream
      }

      /**
        * Recursively releases events in waiting given the id.
        */
      private def release(lsn: Lsn): Pull[F, Nothing, List[Event]] =
        Pull.eval(getLog(lsn).map { causalEvents =>
          for {
            causalEvent <- causalEvents
            event <- decode[CausalWrapper](causalEvent.payload)
              .map(w => Event(causalEvent.lsn, causalEvent.eventType, w.payload))
              .toList
          } yield event
        })
    }
}
