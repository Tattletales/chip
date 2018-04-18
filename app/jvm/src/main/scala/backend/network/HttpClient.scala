package backend.network

import cats.MonadError
import cats.implicits._
import backend.network.HttpClient.Uri
import org.http4s.client.Client
import org.http4s.{Uri => Http4sUri, _}
import shapeless.tag.@@

trait HttpClient[F[_]] {
  def get[Response: EntityDecoder[F, ?]](uri: Uri): F[Response]

  def getAndIgnore[Response: EntityDecoder[F, ?]](uri: Uri): F[Unit]

  def post[T, Response: EntityDecoder[F, ?]](uri: Uri, body: T)(
      implicit T: EntityEncoder[F, T]
  ): F[Response]

  def unsafePost[T, Response: EntityDecoder[F, ?]](uri: Uri, body: T)(
      implicit T: EntityEncoder[F, T]
  ): F[Response]

  def postAndIgnore[T: EntityEncoder[F, ?]](uri: Uri, body: T): F[Unit]

  def unsafePostAndIgnore[T: EntityEncoder[F, ?]](uri: Uri, body: T): F[Unit]
}

object HttpClient extends HttpClientInstances {
  sealed trait UriTag
  type Uri = String @@ UriTag

  def apply[F[_]](implicit H: HttpClient[F]): HttpClient[F] =
    H
}

sealed abstract class HttpClientInstances {
  implicit def http4sClient[F[_]](client: Client[F])(
      implicit F: MonadError[F, Throwable]): HttpClient[F] =
    new HttpClient[F] {
      def get[Response: EntityDecoder[F, ?]](uri: Uri): F[Response] =
        client.expect[Response](uri)

      def getAndIgnore[Response: EntityDecoder[F, ?]](uri: Uri): F[Unit] =
        client.expect[Response](uri).map(_ => ())

      def post[T, Response: EntityDecoder[F, ?]](uri: Uri, body: T)(
          implicit w: EntityEncoder[F, T]
      ): F[Response] =
        client.expect[Response](genPostReq(uri, body))

      def unsafePost[T, Response: EntityDecoder[F, ?]](uri: Uri, body: T)(
          implicit w: EntityEncoder[F, T]
      ): F[Response] =
        genPostReq(uri, body).flatMap(client.expect[Response])

      def postAndIgnore[T: EntityEncoder[F, ?]](uri: Uri, body: T): F[Unit] =
        client.fetch[Unit](genPostReq(uri, body))(_ => F.pure(()))

      def unsafePostAndIgnore[T: EntityEncoder[F, ?]](uri: Uri, body: T): F[Unit] =
        genPostReq(uri, body).flatMap(client.fetch[Unit](_)(_ => F.pure(())))

      private def genPostReq[T](uri: Uri, body: T)(
          implicit T: EntityEncoder[F, T]): F[Request[F]] =
        F.fromEither(Http4sUri.fromString(uri))
          .flatMap { uri =>
            Request(method = Method.POST, uri = uri).withBody(body)(F, T)
          }
          .adaptError {
            case ParseFailure(sanitized, _) => MalformedUriError(uri, sanitized)
          }
    }
}

sealed trait HttpClientError extends Throwable
case class MalformedUriError(uri: String, m: String) extends HttpClientError
