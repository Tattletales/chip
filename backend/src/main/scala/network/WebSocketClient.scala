package network

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink => AkkaSink, Source => AkkaSource}
import cats.MonadError
import cats.effect.{Effect, Timer}
import fs2.{Sink, Stream}
import fs2.async.mutable.Queue
import io.circe.{Decoder, Encoder}
import streamz.converter._
import io.circe.parser.decode
import io.circe.syntax._

import scala.concurrent.Future

trait WebSocketClient[F[_], M1, M2] {
  def send(message: M1): F[Unit]
  def receive: Stream[F, M2]
}

object WebSocketClient {
  def default[F[_]: Timer: Effect, M1: Encoder, M2: Decoder](uri: String)(
      incomingQueue: Queue[F, String],
      outgoingQueue: Queue[F, String]): WebSocketClient[F, M1, M2] =
    new WebSocketClient[F, M1, M2] {
      implicit val system = ActorSystem()
      implicit val materializer = ActorMaterializer()
      import system.dispatcher

      def send(message: M1): F[Unit] = outgoingQueue.enqueue1(message.asJson.noSpaces)
      def receive: Stream[F, M2] =
        incomingQueue.dequeue.evalMap(s => implicitly[MonadError[F, Throwable]].fromEither(decode[M2](s)))

      private val incoming: AkkaSink[Message, Future[Done]] = AkkaSink.fromGraph(Sink[F, Message] {
        case m: TextMessage.Strict   => incomingQueue.enqueue1(m.text)
        case m: TextMessage.Streamed => incomingQueue.enqueue1(m.getStrictText)
      }.toSink)

      private val outgoing: AkkaSource[Message, NotUsed] =
        AkkaSource
          .fromGraph(outgoingQueue.dequeue.map(TextMessage.Strict).toSource)

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
