package backend.gossip

import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, Monad}
import backend.events.Subscriber._
import backend.events.Subscriber.implicits._
import backend.events.{EventTyper, Subscriber}
import fs2.async.mutable.Queue
import fs2.Stream
import backend.gossip.model.Node.{NodeId, NodeIdTag}
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Encoder, Json}
import backend.network.HttpClient
import backend.network.HttpClient.UriTag
import org.http4s.{EntityDecoder, EntityEncoder}
import utils.StreamUtils.log
import shapeless.tag

trait GossipDaemon[F[_]] {
  def getNodeId: F[NodeId]
  def send[M: Encoder](m: M)(implicit M: EventTyper[M]): F[Unit]
  def subscribe
    : Stream[F, Event] // TODO should be val? no need to create new stream for every call
  def getLog: F[List[Event]]
  //def replayLog(lsn: Lsn): F[Unit]
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

      def getNodeId: F[NodeId] = httpClient.get[NodeId](tag[UriTag][String](s"$root/unique"))

      def send[M: Encoder](m: M)(implicit M: EventTyper[M]): F[Unit] =
        httpClient.unsafePostAndIgnore(tag[UriTag][String](s"$root/gossip/${M.eventType}"),
                                       m.asJson)

      def subscribe: Stream[F, Event] = subscriber.subscribe(s"$root/events")

      def getLog: F[List[Event]] = ???
      
      //def replayLog(lsn: Lsn): F[Unit] = ???
    }

  implicit def mock[F[_]: Monad: Sync](eventQueue: Queue[F, Event]): GossipDaemon[F] =
    new GossipDaemon[F] {

      def send[Message: Encoder](m: Message)(implicit M: EventTyper[Message]): F[Unit] =
        eventQueue.enqueue1(
          Event(Lsn(tag[NodeIdTag][String]("FFFF"), tag[EventIdTag][Int](123)),
                M.eventType,
                tag[PayloadTag][String](m.asJson.noSpaces)))

      def getNodeId: F[NodeId] = implicitly[Applicative[F]].pure(tag[NodeIdTag][String]("FFFF"))

      def subscribe: Stream[F, Event] = eventQueue.dequeue.through(log("New event"))

      def getLog: F[List[Event]] = implicitly[Applicative[F]].pure(List.empty)
    }
}
