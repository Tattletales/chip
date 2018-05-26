package backend.gossip

import backend.errors.{LogRetrievalError, NodeIdError, SendError}
import cats.effect.Sync
import java.io._

import cats.effect.{Async, Effect, Sync}
import cats.implicits._
import cats.{Applicative, ApplicativeError, Monad, MonadError}
import backend.events.Subscriber._
import backend.events.{EventTyper, Subscriber}
import backend.gossip.GossipDaemon._
import backend.gossip.Node.{NodeId, NodeIdTag}
import backend.implicits._
import fs2.async.mutable.Queue
import fs2.Stream
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{DecodingFailure, Encoder, Json}
import backend.network.HttpClient
import backend.network.HttpClient.{Root, UriTag}
import org.http4s.{Charset, EntityDecoder, EntityEncoder, UrlForm}
import org.http4s.circe._
import utils.StreamUtils.log
import shapeless.tag
import UrlForm.entityEncoder
import network.WebSocketClient
import shapeless.tag.@@

/**
  * Gossip daemon DSL
  *
  * E is the type of the gossiped events.
  */
trait GossipDaemon[F[_], E] {

  /**
    * The unique id of the current node
    */
  def getNodeId: F[NodeId]

  /**
    * Send an event `E` to all the clients of the daemon.
    */
  def send[E: Encoder](e: E)(implicit E: EventTyper[E]): F[Unit]

  /**
    * Subscribe to the events [[WSEvent]] sent by the daemon.
    */
  def subscribe: Stream[F, E] // TODO should be val? no need to create new stream for every call

  /**
    * Get all the events from the daemon's log.
    */
  def getLog: F[List[E]]
}

object GossipDaemon {
  case class Event(eventType: EventType, payload: Payload)

  /* ------ Interpreters ------*/

  /**
    * Interpret to the [[HttpClient]] and [[Subscriber]] DSLs with
    * events gossiped using ServerSentEvents.
    */
  def serverSentEvent[F[_]](root: Root, nodeId: Option[NodeId] = None)(
      httpClient: HttpClient[F],
      subscriber: Subscriber[F, SSEvent])(
      implicit F: MonadError[F, Throwable]): GossipDaemon[F, SSEvent] =
    new GossipDaemon[F, SSEvent] {

      /**
        * @see [[GossipDaemon.getNodeId]]
        *
        * Failures:
        *   - [[NodeIdError]] if the node id cannot be retrieved.
        */
      def getNodeId: F[NodeId] = nodeId match {
        case Some(nodeId) => F.pure(nodeId)
        case None =>
          httpClient
            .getRaw(tag[UriTag][String](s"$root/unique"))
            .map(tag[NodeIdTag][String])
            .adaptError {
              case _ => NodeIdError
            }

      }

      /**
        * @see [[GossipDaemon.send]]
        *
        * Failures:
        *   - [[SendError]] if the `e` cannot be sent.
        */
      def send[E: Encoder](e: E)(implicit E: EventTyper[E]): F[Unit] = {
        val form = Map(
          "t" -> E.eventType,
          "d" -> e.asJson.noSpaces
        )

        for {
          n <- getNodeId
          _ <- httpClient.postFormAndIgnore(tag[UriTag][String](s"$root/gossip/$n"), form)
        } yield ()
      }

      def subscribe: Stream[F, SSEvent] =
        Stream.force(getNodeId.map(nodeId => subscriber.subscribe(s"$root/events/$nodeId")))

      /**
        * @see [[GossipDaemon.getLog]]
        *
        * Failures:
        *   - [[LogRetrievalError]] if the log cannot be retrieved.
        */
      def getLog: F[List[SSEvent]] =
        httpClient
          .get[Json](tag[UriTag][String](s"$root/log"))
          .flatMap { json =>
            F.fromEither(json.as[List[SSEvent]])
          }
          .adaptError {
            case _ => LogRetrievalError
          }
    }

  /**
    * Interpret to the [[HttpClient]] and [[Subscriber]] DSLs with events
    * gossiped using WebSockets.
    */
  def webSocket[F[_]](root: Root, nodeId: Option[NodeId] = None)(
      httpClient: HttpClient[F],
      ws: WebSocketClient[F])(implicit F: MonadError[F, Throwable]): GossipDaemon[F, WSEvent] =
    new GossipDaemon[F, WSEvent] {

      /**
        * @see [[GossipDaemon.getNodeId]]
        *
        * Failures:
        *   - [[NodeIdError]] if the node id cannot be retrieved.
        */
      def getNodeId: F[NodeId] = nodeId match {
        case Some(nodeId) => F.pure(nodeId)
        case None =>
          httpClient
            .getRaw(tag[UriTag][String](s"$root/unique"))
            .map(tag[NodeIdTag][String])
            .adaptError {
              case _ => NodeIdError
            }

      }

      /**
        * @see [[GossipDaemon.send]]
        *
        * Failures:
        *   - [[SendError]] if the `e` cannot be sent.
        */
      def send[E: Encoder](e: E)(implicit E: EventTyper[E]): F[Unit] =
        ws.send(tag[PayloadTag][String](e.asJson.noSpaces))

      def subscribe: Stream[F, WSEvent] =
        for {
          wsMessage <- ws.receive
          event <- Stream.eval(F.fromEither(Json.fromString(wsMessage).as[WSEvent]))
        } yield event

      /**
        * @see [[GossipDaemon.getLog]]
        *
        * Failures:
        *   - [[LogRetrievalError]] if the log cannot be retrieved.
        */
      def getLog: F[List[WSEvent]] =
        httpClient
          .get[Json](tag[UriTag][String](s"$root/log"))
          .flatMap { json =>
            F.fromEither(json.as[List[WSEvent]])
          }
          .adaptError {
            case _ => LogRetrievalError
          }
    }

  /**
    * Add logging of sent events.
    */
  def logging[F[_], E](path: String)(daemon: GossipDaemon[F, E])(
      implicit F: Effect[F]): GossipDaemon[F, E] =
    new GossipDaemon[F, E] {
      private val file = new File(path)
      private val bw = new BufferedWriter(new FileWriter(file, true))

      /**
        * @see [[GossipDaemon.getNodeId]]
        */
      def getNodeId: F[NodeId] = daemon.getNodeId

      /**
        * @see [[GossipDaemon.send]]
        *
        * Logs `e`.
        */
      def send[E: Encoder](e: E)(implicit E: EventTyper[E]): F[Unit] = {
        println("SEND")
        log(e) >> daemon.send(e)
      }

      /**
        * @see [[GossipDaemon.subscribe]]
        */
      def subscribe: Stream[F, E] = daemon.subscribe

      /**
        * @see [[GossipDaemon.getLog]]
        */
      def getLog: F[List[E]] = daemon.getLog

      /**
        * Log `e` to the file at path [[path]]
        */
      private def log[E](e: E): F[Unit] =
        F.delay {
          bw.write(s"${System.currentTimeMillis()} $e SEND\n")
          bw.flush()
        }
    }

  /**
    * Interprets to `FS2` queues.
    *
    * Mock version of the [[GossipDaemon]] using ServerSentEvents.
    *
    * Will always yield the same node id and simply echoes back the events.
    * [[GossipDaemon.getLog]] will never return a result.
    */
  def sseMock[F[_]](eventQueue: Queue[F, SSEvent])(implicit F: Sync[F]): GossipDaemon[F, SSEvent] =
    new GossipDaemon[F, SSEvent] {
      def getNodeId: F[NodeId] = F.pure(tag[NodeIdTag][String]("MyOwnKey"))

      def send[E: Encoder](e: E)(implicit E: EventTyper[E]): F[Unit] =
        eventQueue.enqueue1(
          SSEvent(Lsn(tag[NodeIdTag][String]("MyOwnKey"), tag[EventIdTag][Int](123)),
                  E.eventType,
                  tag[PayloadTag][String](e.asJson.noSpaces)))

      def subscribe: Stream[F, SSEvent] = eventQueue.dequeue.through(log("New event"))

      def getLog: F[List[SSEvent]] = F.pure(List.empty)
    }
}
