import Subscriber.Event
import Utils.log
import cats.effect.Sync
import fs2.Stream
import fs2.async.Ref
import fs2.async.mutable.Queue
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Encoder, Json}
import org.http4s.{EntityDecoder, EntityEncoder}

trait GossipDaemon[F[_]] {
  def getUniqueId: F[String]
  def send[Message: Encoder: EventTypable](m: Message): F[Unit]
}

object GossipDaemon extends GossipDaemonInstances {
  def apply[F[_]](implicit D: GossipDaemon[F]): GossipDaemon[F] = D
}

sealed abstract class GossipDaemonInstances {
  implicit def localhost[F[_], G[_]: EntityDecoder[?[_], String]: EntityEncoder[?[_], Json]](
      httpClient: HttpClient[F, G]): GossipDaemon[F] =
    new GossipDaemon[F] {
      private val root = "localhost:2018"

      def getUniqueId: F[String] = httpClient.get[String](s"$root/unique")

      def send[Message: Encoder: EventTypable](m: Message): F[Unit] =
        httpClient.unsafePostAndIgnore(s"$root/gossip", m.asJson)
    }

  implicit def mock[F[_]: Sync](eventQueue: Queue[F, Event],
                                counter: Ref[F, Int]): GossipDaemon[Stream[F, ?]] =
    new GossipDaemon[Stream[F, ?]] {

      def send[Message: Encoder: EventTypable](m: Message): Stream[F, Unit] =
        Stream
          .eval(
            eventQueue.enqueue1(
              Event(implicitly[EventTypable[Message]].eventType, m.asJson.spaces2)))

      def getUniqueId: Stream[F, String] =
        for {
          _ <- Stream.eval(counter.modify(_ + 1))
          uid <- Stream.eval(counter.get)
        } yield uid.toString
    }
}
