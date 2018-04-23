package backend.network

import backend.network.HttpClient.Uri
import cats.MonadError
import cats.effect.Sync
import cats.implicits._
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.{Uri => Http4sUri, _}
import shapeless.tag
import shapeless.tag.@@

/**
  * HTTP client DSL
  */
trait HttpClient[F[_]] {
  def get[Response: Decoder](uri: Uri): F[Response]

  def getAndIgnore(uri: Uri): F[Unit]

  def post[T: Encoder, Response: Decoder](uri: Uri, body: T): F[Response]

  def postAndIgnore[T: Encoder](uri: Uri, body: T): F[Unit]
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
  def http4sClient[F[_]](root: Root)(client: Client[F])(implicit F: Sync[F]): HttpClient[F] =
    new HttpClient[F] {
      def get[Response: Decoder](relUri: Uri): F[Response] =
        client.expect(root ++ relUri)(jsonOf[F, Response])

      def getAndIgnore(relUri: Uri): F[Unit] = {
        val uri = tag[UriTag][String](root ++ relUri)
        client.get(uri) {
          case Status.Successful(_) => F.pure(())
          case _                    => F.raiseError[Unit](FailedRequestResponse(uri))
        }
      }

      def post[T: Encoder, Response: Decoder](relUri: Uri, body: T): F[Response] =
        client.expect(genPostReq(tag[UriTag][String](root ++ relUri), body.asJson))(
          jsonOf[F, Response])

      def postAndIgnore[T: Encoder](relUri: Uri, body: T): F[Unit] = {
        val uri = tag[UriTag][String](root ++ relUri)
        client.fetch(genPostReq(uri, body.asJson)) {
          case Status.Successful(_) => F.pure(())
          case _                    => F.raiseError(FailedRequestResponse(uri))
        }
      }

      private def genPostReq[T](relUri: Uri, body: Json): F[Request[F]] = {
        val uri = tag[UriTag][String](root ++ relUri)
        F.fromEither(Http4sUri.fromString(uri))
          .flatMap { uri =>
            Request(method = Method.POST, uri = uri).withBody(body)(F, EntityEncoder[F, Json])
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
  case class FailedRequestResponse(uri: Uri) extends HttpClientError
}
