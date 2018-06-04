package backend.network

import akka.{Done, NotUsed}
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{Uri => AkkaUri}
import akka.http.scaladsl.model.ws.{Message, TextMessage, WebSocketRequest}
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{Keep, Sink => AkkaSink, Source => AkkaSource}
import cats.ApplicativeError
import cats.effect.{Effect, Timer}
import cats.implicits._
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
  def akkaHttp[F[_]: Timer: Effect, M1: Encoder, M2: Decoder](
      route: Route)(incomingQueue: Queue[F, M2], outgoingQueue: Queue[F, M1])(
      implicit F: ApplicativeError[F, Throwable]): WebSocketClient[F, M1, M2] =
    new WebSocketClient[F, M1, M2] {
      implicit val system = ActorSystem()
      implicit val materializer = ActorMaterializer()

      def send(message: M1): F[Unit] = outgoingQueue.enqueue1(message)
      def receive: Stream[F, M2] = incomingQueue.dequeue

      private val incoming: AkkaSink[Message, Future[Done]] = AkkaSink.fromGraph(Sink[F, Message] {
        case m: TextMessage.Strict =>
          F.fromEither(decode[M2](m.text)).flatMap(incomingQueue.enqueue1)
        case m: TextMessage.Streamed =>
          F.fromEither(decode[M2](m.getStrictText)).flatMap(incomingQueue.enqueue1)
      }.toSink)

      private val outgoing: AkkaSource[Message, NotUsed] =
        AkkaSource
          .fromGraph(
            outgoingQueue.dequeue.map(json => TextMessage.Strict(json.asJson.noSpaces)).toSource)

      private val webSocketFlow = Http().webSocketClientFlow(WebSocketRequest(AkkaUri(route.value)))

      private val (upgradeResponse, _) =
        outgoing
          .viaMat(webSocketFlow)(Keep.right) // keep the materialized Future[WebSocketUpgradeResponse]
          .toMat(incoming)(Keep.both) // also keep the Future[Done]
          .run()

      //private val _ = upgradeResponse.flatMap { upgrade =>
      //  if (upgrade.response.status == StatusCodes.SwitchingProtocols) {
      //    Future.successful(Done)
      //  } else {
      //    throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
      //  }
      //}
    }
}
