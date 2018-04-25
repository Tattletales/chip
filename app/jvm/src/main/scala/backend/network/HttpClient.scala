package backend.network

import backend.errors.{FailedRequestResponse, MalformedUriError}
import backend.network.HttpClient.Uri
import cats.effect.Sync
import cats.implicits._
import io.circe.{Decoder, Encoder, Json}
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.MediaType.`application/x-www-form-urlencoded`
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.{Uri => Http4sUri, _}
import shapeless.tag
import shapeless.tag.@@

/**
  * HTTP client DSL
  *
  * Data flowing through the HTTP client is always encoded as Json
  */
trait HttpClient[F[_]] {

  /**
    * Get request and return the response as a string.
    */
  def getRaw(uri: Uri): F[String]

  /**
    * Get request
    */
  def get[Response: Decoder](uri: Uri): F[Response]

  /**
    * Get request and ignore the response.
    */
  def getAndIgnore(uri: Uri): F[Unit]

  /**
    * Post request
    */
  def post[T: Encoder, Response: Decoder](uri: Uri, body: T): F[Response]

  /**
    * Post request and ignore the response
    */
  def postAndIgnore[T: Encoder](uri: Uri, body: T): F[Unit]

  /**
    * Post a form and ignore the response
    */
  def postFormAndIgnore(uri: Uri, form: Map[String, String]): F[Unit]
}

object HttpClient {

  /* ------ Interpreters ------ */

  /**
    * Interpreter to `Http4s`.
    */
  def default[F[_]](client: Client[F])(implicit F: Sync[F]): HttpClient[F] =
    new HttpClient[F] {

      def getRaw(uri: Uri): F[String] =
        client.expect[String](uri)

      def get[Response: Decoder](uri: Uri): F[Response] =
        client.expect(uri)(jsonOf[F, Response])

      /**
        * @see [[HttpClient.getAndIgnore]]
        *
        * Fails with [[FailedRequestResponse]] if the request failed.
        */
      def getAndIgnore(uri: Uri): F[Unit] =
        client.get(uri) {
          case Status.Successful(_) => F.unit
          case _                    => F.raiseError[Unit](FailedRequestResponse(uri))
        }

      def post[T: Encoder, Response: Decoder](uri: Uri, body: T): F[Response] =
        client.expect(genPostReq(uri, body.asJson.noSpaces))(jsonOf[F, Response])

      /**
        * @see [[HttpClient.postAndIgnore]]
        *
        * Failures:
        *   - [[FailedRequestResponse]] if the request failed
        *   - [[MalformedUriError]] if the `uri` is malformed
        */
      def postAndIgnore[T: Encoder](uri: Uri, body: T): F[Unit] =
        client.fetch(genPostReq(uri, body.asJson.noSpaces)) {
          case Status.Successful(_) => F.unit
          case _                    => F.raiseError(FailedRequestResponse(uri))
        }

      /**
        * @see [[HttpClient.postFormAndIgnore]]
        *
        * Failures:
        *   - [[FailedRequestResponse]] if the request failed
        *   - [[MalformedUriError]] if the `uri` is malformed
        */
      def postFormAndIgnore(uri: Uri, form: Map[String, String]): F[Unit] = {
        val form0 = UrlForm(form.toSeq: _*)
        val body = UrlForm.encodeString(Charset.`UTF-8`)(form0)

        client.fetch(
          genPostReq(uri, body).map(
            _.withContentType(`Content-Type`(`application/x-www-form-urlencoded`)))) {
          case Status.Successful(_) => F.unit
          case _                    => F.raiseError(FailedRequestResponse(uri))
        }
      }

      /**
        * Generate a POST request.
        *
        * Fails with [[MalformedUriError]] if the URI is invalid.
        */
      private def genPostReq(uri: Uri, body: String): F[Request[F]] =
        F.fromEither(Http4sUri.fromString(uri))
          .flatMap { uri =>
            Request(method = Method.POST, uri = uri).withBody(body)(F, EntityEncoder[F, String])
          }
          .adaptError {
            case ParseFailure(sanitized, _) => MalformedUriError(uri, sanitized)
          }
    }

  /* ------ Types ------ */

  sealed trait UriTag
  type Uri = String @@ UriTag

  sealed trait RootTag
  type Root = String @@ RootTag

}
