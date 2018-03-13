import Subscriber.Event
import Utils.log
import cats.Monad
import cats.effect.Sync
import cats.implicits._
import fs2.Stream
import fs2.async.Ref
import fs2.async.mutable.Queue
import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.http4s.{EntityDecoder, EntityEncoder}

trait GossipDaemon[F[_]] {
  def getUniqueId: F[String]
  def send[Message: Encoder: EventTypable](m: Message): F[Unit]
  def subscribe
    : Stream[F, Event] // TODO should be val? no need to create new stream for every call
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

      def send[Message: Encoder: EventTypable](m: Message): F[Unit] =
        httpClient.unsafePostAndIgnore(s"$root/gossip", m.asJson)

      def subscribe: Stream[F, Event] = subscriber.subscribe(s"$root/events")
    }

  implicit def mock[F[_]: Monad: Sync](eventQueue: Queue[F, Event],
                                       counter: Ref[F, Int]): GossipDaemon[F] =
    new GossipDaemon[F] {

      def send[Message: Encoder: EventTypable](m: Message): F[Unit] =
        eventQueue.enqueue1(Event(implicitly[EventTypable[Message]].eventType, m.asJson.spaces2))

      def getUniqueId: F[String] =
        (counter.modify(_ + 1) >> counter.get).map(_.toString)

      def subscribe: Stream[F, Event] = eventQueue.dequeue.through(log("New event"))
    }
}
