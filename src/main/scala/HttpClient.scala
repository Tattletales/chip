import cats.effect._
import cats.implicits._
import fs2.Stream._
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.client._
import org.http4s.client.blaze.{BlazeClientConfig, Http1Client}
import org.http4s.client.dsl.Http4sClientDsl

trait HttpClient[F[_], Request] {
  def get[Response](request: Request): F[Option[Response]]
  def put[T](request: Request, put: T): F[Unit]
}

object HttpClient extends HttpClientInstances {
  def apply[F[_], Request](
      implicit H: HttpClient[F, Request]): HttpClient[F, Request] = H
}

sealed abstract class HttpClientInstances {
  implicit def http4sClient: HttpClient[IO, String] =
    new HttpClient[IO, String] {
      private[this] val client =
        Http1Client.stream[IO](BlazeClientConfig.defaultConfig)

      def get[Response](request: String): IO[Option[Response]] = {
        client
          .map(_.expect[Response](uri(request)))
          .compile
          .last
          .flatMap(_.sequence[IO, Response])
        
        Http4sClientDsl
      }

      def put[T](request: String, put: T): IO[Unit] = ???
    }
}
