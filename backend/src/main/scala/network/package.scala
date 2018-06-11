package backend

import cats.effect.{Effect, Sync, Timer}
import cats.implicits._
import eu.timepit.refined.api.Refined
import eu.timepit.refined.string.{Uri => RefUri}
import io.circe.{Decoder, Encoder}
import org.http4s.client.blaze.Http1Client
import fs2.Stream

import scala.concurrent.ExecutionContext

package object network {
  type Uri = String Refined RefUri
  type Route = Uri

  /**
    * Creates a [[WebSocketClient]].
    */
  def webSocketClient[F[_]: Timer: Effect, M1: Encoder, M2: Decoder](route: Route)(
      implicit ec: ExecutionContext): F[WebSocketClient[F, M1, M2]] =
    for {
      incomingQueue <- fs2.async.unboundedQueue[F, M2]
      outgoingQueue <- fs2.async.unboundedQueue[F, M1]
    } yield WebSocketClient.akkaHttp[F, M1, M2](route)(incomingQueue, outgoingQueue)

  /**
    * Creates an [[HttpClient]]. Unsafe as the client resources may not be released automatically.
    */
  def unsafeHttpClient[F[_]: Effect]: F[HttpClient[F]] =
    Http1Client().map(HttpClient.default(_)(implicitly[Sync[F]]))

  /**
    * Creates an [[HttpClient]] where the client resources are release automatically.
    */
  def httpClient[F[_]: Effect]: Stream[F, HttpClient[F]] =
    Http1Client.stream().map(HttpClient.default(_)(implicitly[Sync[F]]))
}
