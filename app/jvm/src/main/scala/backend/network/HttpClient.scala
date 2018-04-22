package backend.network

import backend.network.HttpClient.Uri
import cats.MonadError
import cats.implicits._
import org.http4s.client.Client
import org.http4s.{Uri => Http4sUri, _}
import shapeless.tag
import shapeless.tag.@@

/**
  * HTTP client DSL
  */
trait HttpClient[F[_]] {
  def get[Response: EntityDecoder[F, ?]](uri: Uri): F[Response]

  def getAndIgnore[Response: EntityDecoder[F, ?]](uri: Uri): F[Unit]

  def post[T, Response: EntityDecoder[F, ?]](uri: Uri, body: T)(
      implicit T: EntityEncoder[F, T]
  ): F[Response]

  def postAndIgnore[T: EntityEncoder[F, ?]](uri: Uri, body: T): F[Unit]
}

object HttpClient {

  /* ------ Interpreters ------ */

  /**
    * Interpreter to `Http4s`.
    *
    * Warning: takes relative uri's.
    *
    * @param root the root
    */
  def http4sClient[F[_]](root: Root)(client: Client[F])(
      implicit F: MonadError[F, Throwable]): HttpClient[F] =
    new HttpClient[F] {
      def get[Response: EntityDecoder[F, ?]](relUri: Uri): F[Response] =
        client.expect[Response](root ++ relUri)

      def getAndIgnore[Response: EntityDecoder[F, ?]](relUri: Uri): F[Unit] =
        get(tag[UriTag][String](root ++ relUri)).map(_ => ())

      def post[T, Response: EntityDecoder[F, ?]](relUri: Uri, body: T)(
          implicit T: EntityEncoder[F, T]
      ): F[Response] =
        client.expect[Response](genPostReq(tag[UriTag][String](root ++ relUri), body))

      def postAndIgnore[T: EntityEncoder[F, ?]](relUri: Uri, body: T): F[Unit] =
        client.fetch[Unit](genPostReq(tag[UriTag][String](root ++ relUri), body))(_ => F.pure(()))

      private def genPostReq[T](relUri: Uri, body: T)(
          implicit T: EntityEncoder[F, T]): F[Request[F]] = {
        val uri = tag[UriTag][String](root ++ relUri)
        F.fromEither(Http4sUri.fromString(uri))
          .flatMap { uri =>
            Request(method = Method.POST, uri = uri).withBody(body)(F, T)
          }
          .adaptError {
            case ParseFailure(sanitized, _) =>
              MalformedUriError(uri, sanitized)
          }
      }
    }

  /* ------ Types ------ */

  sealed trait UriTag
  type Uri = String @@ UriTag

  sealed trait RootTag
  type Root = String @@ RootTag

  /* ------ Errors ------ */

  sealed trait HttpClientError extends Throwable
  case class MalformedUriError(uri: Uri, m: String) extends HttpClientError
}
