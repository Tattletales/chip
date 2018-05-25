package network

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink => AkkaSink, Source => AkkaSource}
import cats.effect.{Effect, Timer}
import fs2.{Sink, Stream}
import fs2.async.mutable.Queue
import streamz.converter._

import scala.concurrent.Future

trait WebSocketClient[F[_]] {
  def send(message: String): F[Unit]
  def receive: Stream[F, String]
}

object WebSocketClient {
  def default[F[_]: Timer: Effect](uri: String)(incomingQueue: Queue[F, String],
                           outgoingQueue: Queue[F, String]): WebSocketClient[F] =
    new WebSocketClient[F] {
      implicit val system = ActorSystem()
      implicit val materializer = ActorMaterializer()
      import system.dispatcher

      def send(message: String): F[Unit] = outgoingQueue.enqueue1(message)
      def receive: Stream[F, String] = incomingQueue.dequeue

      private val incoming: AkkaSink[Message, Future[Done]] = AkkaSink.fromGraph(Sink[F, Message] {
        case m: TextMessage.Strict   => incomingQueue.enqueue1(m.text)
        case m: TextMessage.Streamed => incomingQueue.enqueue1(m.getStrictText)
      }.toSink)

      private val outgoing: AkkaSource[Message, NotUsed] =
        AkkaSource.fromGraph(outgoingQueue.dequeue.map(TextMessage.Strict).toSource)

      private val webSocketFlow = Http().webSocketClientFlow(WebSocketRequest(uri))

      private val (upgradeResponse, closed) =
        outgoing
          .viaMat(webSocketFlow)(Keep.right) // keep the materialized Future[WebSocketUpgradeResponse]
          .toMat(incoming)(Keep.both) // also keep the Future[Done]
          .run()

      private val connected = upgradeResponse.flatMap { upgrade =>
        if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
          Future.successful(Done)
        } else {
          throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
        }
      }

    }
}
