package backend.network

import backend.errors.FailedRequestResponse
import cats.effect.Sync
import cats.implicits._
import io.circe.{Decoder, Encoder}
import io.circe.syntax._
import org.http4s.MediaType.`application/x-www-form-urlencoded`
import org.http4s.circe._
import org.http4s.client.Client
import org.http4s.headers.`Content-Type`
import org.http4s.{Uri => Http4sUri, _}

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
        client.expect[String](uri.value)

      def get[Response: Decoder](uri: Uri): F[Response] =
        client.expect(Http4sUri.unsafeFromString(uri.value))(jsonOf[F, Response])

      /**
        * @see [[HttpClient.getAndIgnore]]
        *
        * Fails with [[FailedRequestResponse]] if the request failed.
        */
      def getAndIgnore(uri: Uri): F[Unit] =
        client.get(Http4sUri.unsafeFromString(uri.value)) {
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
        */
      private def genPostReq(uri: Uri, body: String): F[Request[F]] =
        Request(method = Method.POST, uri = Http4sUri.unsafeFromString(uri.value))
          .withBody(body)(F, EntityEncoder[F, String])

    }
}
