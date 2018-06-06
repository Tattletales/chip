package backend.gossip

import java.io._

import backend.errors.{LogRetrievalError, NodeIdError}
import backend.events._
import backend.implicits._
import backend.gossip.Node.{NodeId, NodeIdTag}
import backend.network.{HttpClient, Route, WebSocketClient}
import cats.arrow.Profunctor
import cats.effect.{Effect, Sync}
import cats.implicits._
import cats.{Functor, MonadError}
import fs2.Stream
import fs2.async.mutable.Queue
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.{Encoder, Json}
import shapeless.tag
import utils.stream.Utils.log

/**
  * Gossip daemon DSL
  *
  * E is the type of the gossiped events.
  */
trait GossipDaemon[F[_], E1, E2] {

  /**
    * The unique id of the current node
    */
  def getNodeId: F[NodeId]

  /**
    * Send an event `E` to all the clients of the daemon.
    */
  def send(e: E1): F[Unit]

  /**
    * Subscribe to the events [[WSEvent]] sent by the daemon.
    */
  def subscribe: Stream[F, E2]

  /**
    * Get all the events from the daemon's log.
    */
  def getLog: F[List[E2]]
}

object GossipDaemon {
  /* ------ Interpreters ------*/

  /**
    * Interpret to the [[HttpClient]] and [[Subscription]] DSLs with
    * events gossiped using ServerSentEvents.
    */
  def serverSentEvent[F[_], E: Encoder](nodeIdRoute: Route,
                                        sendRoute: Route,
                                        subscribeRoute: Route,
                                        logRoute: Route)(
      nodeId: Option[NodeId])(httpClient: HttpClient[F], subscriber: Subscription[F, SSEvent])(
      implicit F: MonadError[F, Throwable],
      E: EventTyper[E]): GossipDaemon[F, E, SSEvent] =
    new GossipDaemon[F, E, SSEvent] {

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
            .getRaw(nodeIdRoute)
            .map(tag[NodeIdTag][String])
            .adaptError {
              case _ => NodeIdError
            }

      }

      def send(e: E): F[Unit] = {
        val form = Map(
          "t" -> E.eventType,
          "d" -> e.asJson.noSpaces
        )

        for {
          n <- getNodeId
          _ <- httpClient.postFormAndIgnore(sendRoute, form)
        } yield ()
      }

      def subscribe: Stream[F, SSEvent] = subscriber.subscribe(subscribeRoute)

      /**
        * @see [[GossipDaemon.getLog]]
        *
        * Failures:
        *   - [[LogRetrievalError]] if the log cannot be retrieved.
        */
      def getLog: F[List[SSEvent]] =
        httpClient
          .get[Json](logRoute)
          .flatMap { json =>
            F.fromEither(json.as[List[SSEvent]])
          }
          .adaptError {
            case _ => LogRetrievalError
          }
    }

  /**
    * Interpret to the [[HttpClient]] and [[Subscription]] DSLs with events
    * gossiped using WebSockets.
    */
  def webSocket[F[_], E: Encoder](nodeIdRoute: Route, logRoute: Route)(
      nodeId: Option[NodeId])(httpClient: HttpClient[F], ws: WebSocketClient[F, E, WSEvent])(
      implicit F: MonadError[F, Throwable]): GossipDaemon[F, E, WSEvent] =
    new GossipDaemon[F, E, WSEvent] {

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
            .getRaw(nodeIdRoute)
            .map(tag[NodeIdTag][String])
            .adaptError {
              case _ => NodeIdError
            }

      }

      def send(e: E): F[Unit] = ws.send(e)

      def subscribe: Stream[F, WSEvent] = ws.receive

      /**
        * @see [[GossipDaemon.getLog]]
        *
        * Failures:
        *   - [[LogRetrievalError]] if the log cannot be retrieved.
        */
      def getLog: F[List[WSEvent]] =
        httpClient
          .get[Json](logRoute)
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
  def logging[F[_], E1, E2](path: String)(daemon: GossipDaemon[F, E1, E2])(
      implicit F: Effect[F]): GossipDaemon[F, E1, E2] =
    new GossipDaemon[F, E1, E2] {
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
      def send(e: E1): F[Unit] =
        log(e) >> daemon.send(e)

      /**
        * @see [[GossipDaemon.subscribe]]
        */
      def subscribe: Stream[F, E2] = daemon.subscribe

      /**
        * @see [[GossipDaemon.getLog]]
        */
      def getLog: F[List[E2]] = daemon.getLog

      /**
        * Log `e` to the file at path [[path]]
        */
      private def log[E](e: E): F[Unit] =
        daemon.getNodeId.map { nId =>
          bw.write(s"${System.currentTimeMillis()} $nId SENT $e \n")
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
  def sseMock[F[_], E: Encoder](eventQueue: Queue[F, SSEvent])(
      implicit F: Sync[F],
      E: EventTyper[E]): GossipDaemon[F, E, SSEvent] =
    new GossipDaemon[F, E, SSEvent] {
      def getNodeId: F[NodeId] = F.pure(tag[NodeIdTag][String]("MyOwnKey"))

      def send(e: E): F[Unit] =
        eventQueue.enqueue1(
          SSEvent(Lsn(tag[NodeIdTag][String]("MyOwnKey"), tag[EventIdTag][Int](123)),
                  E.eventType,
                  tag[PayloadTag][Json](e.asJson)))

      def subscribe: Stream[F, SSEvent] = eventQueue.dequeue.through(log("New event"))

      def getLog: F[List[SSEvent]] = F.pure(List.empty)
    }

  implicit def gossipDaemonProfunctor[F[_]: Functor]: Profunctor[GossipDaemon[F, ?, ?]] =
    new Profunctor[GossipDaemon[F, ?, ?]] {
      def dimap[A, B, C, D](daemon: GossipDaemon[F, A, B])(f: C => A)(
          g: B => D): GossipDaemon[F, C, D] = new GossipDaemon[F, C, D] {
        def getNodeId: F[NodeId] = daemon.getNodeId
        def send(e: C): F[Unit] = daemon.send(f(e))
        def subscribe: Stream[F, D] = daemon.subscribe.map(g)
        def getLog: F[List[D]] = daemon.getLog.map(_.map(g))
      }
    }
}
