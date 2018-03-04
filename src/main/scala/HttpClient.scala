import cats.effect._
import cats.implicits._
import fs2.Stream
import org.http4s._
import org.http4s.dsl._
import org.http4s.client._
import org.http4s.client.blaze.{BlazeClientConfig, Http1Client}
import org.http4s.client.dsl.Http4sClientDsl

trait HttpClient[F[_], G[_], Request] {
  def get[Response: EntityDecoder[G, ?]](request: Request): F[Response]
  def post[T](request: Request, put: T): F[Unit]
}

object HttpClient extends HttpClientInstances {
  def apply[F[_], G[_], Request](implicit H: HttpClient[F, G, Request]): HttpClient[F, G, Request] = H
}

sealed abstract class HttpClientInstances {
  implicit def http4sClient[F[_]: Effect]: HttpClient[Stream[F, ?], F, String] =
    new HttpClient[Stream[F, ?], F, String] {
      private[this] val safeClient = Http1Client.stream[F]()

      def get[Response: EntityDecoder[F, ?]](request: String): Stream[F, Response] =
        for {
          client <- safeClient
          out <- Stream.eval(client.expect[Response](request))
        } yield out

      // TODO implement
      def post[T](request: String, put: T): Stream[F, Unit] =
        Stream.eval(implicitly[Effect[F]].pure(()))
    }
}
