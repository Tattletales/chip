import cats.effect.Effect
import fs2.Stream
import io.circe.Decoder
import org.http4s._
import org.http4s.circe._
import org.http4s.client.blaze._

trait HttpClient[F[_], Request] {
  def get[Response](request: Request): F[Response]
  def put[Response, T](request: Request, put: T): F[Response]
}

object HttpClient extends HttpClientInstances {
  def apply[F[_], Request](
      implicit H: HttpClient[F, Request]): HttpClient[F, Request] = H
}

sealed abstract class HttpClientInstances {
  type Http[F[_], R] = Stream[F, F[R]]

  implicit def http4sClient[F[_]: Effect]: HttpClient[Http[F, ?], String] =
    new HttpClient[Http[F, ?], String] {
      private[this] val safeClient =
        Http1Client.stream[F](BlazeClientConfig.defaultConfig)

      def get[Response: Decoder](request: String): Http[F, Response] = {
        val req: Request[F] = ???
        safeClient.map(r => r.expect(req)(jsonOf[F, Response]))
      }

      def put[Response: Decoder, T](request: String, put: T): Http[F, Response] = {
        val req: Request[F] = ???
        safeClient.map(r => r.expect(req)(jsonOf[F, Response]))
      }

    }
}
