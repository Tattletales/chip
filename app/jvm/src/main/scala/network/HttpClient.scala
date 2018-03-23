package network

import cats.MonadError
import cats.implicits._
import network.HttpClient.Uri
import org.http4s.client.Client
import org.http4s.{Uri => Http4sUri, _}
import shapeless.tag.@@

trait HttpClient[F[_]] {
  def get[Response: EntityDecoder[F, ?]](uri: Uri): F[Response]

  def post[T, Response: EntityDecoder[F, ?]](uri: Uri, body: T)(
      implicit T: EntityEncoder[F, T]
  ): F[Option[Response]]

  def unsafePost[T, Response: EntityDecoder[F, ?]](uri: Uri, body: T)(
      implicit T: EntityEncoder[F, T]
  ): F[Response]

  def postAndIgnore[T: EntityEncoder[F, ?]](uri: Uri, body: T): F[Option[Unit]]

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

      def post[T, Response: EntityDecoder[F, ?]](uri: Uri, body: T)(
          implicit w: EntityEncoder[F, T]
      ): F[Option[Response]] =
        genPostReq(uri, body).toOption.traverse[F, Response]((req: F[Request[F]]) =>
          client.expect[Response](req))

      def unsafePost[T, Response: EntityDecoder[F, ?]](uri: Uri, body: T)(
          implicit w: EntityEncoder[F, T]
      ): F[Response] =
        F.fromEither(genPostReq(uri, body)).flatMap(client.expect[Response])

      def postAndIgnore[T: EntityEncoder[F, ?]](uri: Uri, body: T): F[Option[Unit]] =
        genPostReq(uri, body).toOption.traverse[F, Unit](req =>
          client.fetch[Unit](req)(_ => F.pure(())))

      def unsafePostAndIgnore[T: EntityEncoder[F, ?]](uri: Uri, body: T): F[Unit] =
        F.fromEither(genPostReq(uri, body)).flatMap(client.fetch[Unit](_)(_ => F.pure(())))

      private def genPostReq[T: EntityEncoder[F, ?]](
          uri: Uri,
          body: T): Either[ParseFailure, F[Request[F]]] =
        Http4sUri.fromString(uri).map {
          Request().withMethod(Method.POST).withUri(_).withBody(body)
        }
    }
}
