package backend.gossip

import cats.effect.Sync
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, MonadError}
import backend.events.Subscriber._
import backend.events.{EventTyper, Subscriber}
import backend.gossip.GossipDaemon._
import backend.gossip.model.Node.{NodeId, NodeIdTag}
import backend.implicits._
import fs2.async.mutable.Queue
import fs2.Stream
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Encoder, Json}
import backend.network.HttpClient
import backend.network.HttpClient.{Root, UriTag}
import org.http4s.{EntityDecoder, EntityEncoder}
import org.http4s.circe._
import utils.StreamUtils.log
import shapeless.tag
import shapeless.tag.@@

/**
  * Gossip daemon DSL
  */
trait GossipDaemon[F[_]] {

  /**
    * The unique id of the current node
    */
  def getNodeId: F[NodeId]

  /**
    * Send an event `E` to all the clients of the daemon.
    */
  def send[E: Encoder](e: E)(implicit E: EventTyper[E]): F[Unit]

  /**
    * Subscribe to the events [[Event]] sent by the daemon.
    */
  def subscribe: Stream[F, Event] // TODO should be val? no need to create new stream for every call

  /**
    * Get all the events from the daemon's log.
    */
  def getLog: F[List[Event]]
}

object GossipDaemon {
  /* ------ Interpreters ------*/

  /**
    * Interpret to the [[HttpClient]] and [[Subscriber]] DSLs.
    */
  def relativeHttpClient[F[_]](root: Root)(httpClient: HttpClient[F], subscriber: Subscriber[F])(
      implicit F: MonadError[F, Throwable]): GossipDaemon[F] =
    new GossipDaemon[F] {
      def getNodeId: F[NodeId] =
        F.adaptError(
          httpClient.getRaw(tag[UriTag][String](s"$root/unique")).map(tag[NodeIdTag][String])) {
          case _ => NodeIdError
        }

      def send[E: Encoder](e: E)(implicit E: EventTyper[E]): F[Unit] =
        F.adaptError(httpClient.getAndIgnore(
          tag[UriTag][String](s"$root/gossip?t=${E.eventType.toString}&d=${e.asJson.noSpaces}"))) {
          case _ => SendError
        }

      def subscribe: Stream[F, Event] = subscriber.subscribe(s"$root/events")

      def getLog: F[List[Event]] =
        F.adaptError(
          httpClient
            .get[Json](tag[UriTag][String](s"$root/log"))
            .map(_.as[List[Event]].right.get)) {
          case _ => LogRetrievalError
        }
    }

  /**
    * Interprets to `FS2` queues.
    *
    * Mock version of the [[GossipDaemon]].
    *
    * Will always yield the same node id and simply echoes back the events.
    * [[GossipDaemon.getLog]] will never return a result.
    */
  def mock[F[_]](eventQueue: Queue[F, Event])(implicit F: Sync[F]): GossipDaemon[F] =
    new GossipDaemon[F] {
      def getNodeId: F[NodeId] = F.pure(tag[NodeIdTag][String]("MyOwnKey"))

      def send[E: Encoder](e: E)(implicit E: EventTyper[E]): F[Unit] =
        eventQueue.enqueue1(
          Event(Lsn(tag[NodeIdTag][String]("MyOwnKey"), tag[EventIdTag][Int](123)),
                E.eventType,
                tag[PayloadTag][String](e.asJson.noSpaces)))

      def subscribe: Stream[F, Event] = eventQueue.dequeue.through(log("New event"))

      def getLog: F[List[Event]] = F.pure(List.empty)
    }

  /* ------ Errors ------ */
  sealed trait GossipDeamonError extends Throwable
  case object NodeIdError extends GossipDeamonError {
    override def toString: String = "Could not retrieve the node id."
  }
  case object SendError extends GossipDeamonError
  case object LogRetrievalError extends GossipDeamonError
}
