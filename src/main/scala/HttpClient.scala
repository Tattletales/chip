import cats.Monad
import cats.effect._
import fs2.Stream
import org.http4s._
import org.http4s.client.blaze.Http1Client

trait HttpClient[F[_], G[_], Request] {
  def get[Response: EntityDecoder[G, ?]](request: Request): F[Response]
  def post[T, Response: EntityDecoder[G, ?]](request: Request, put: T)(
    implicit T: EntityEncoder[G, T]
  ): F[Response]
}

object HttpClient extends HttpClientInstances {
  def apply[F[_], G[_], Request](implicit H: HttpClient[F, G, Request]): HttpClient[F, G, Request] =
    H
}

sealed abstract class HttpClientInstances {
  implicit def http4sClient[F[_]: Monad: Effect]: HttpClient[Stream[F, ?], F, String] =
    new HttpClient[Stream[F, ?], F, String] {
      private[this] val safeClient = Http1Client.stream[F]()

      def get[Response: EntityDecoder[F, ?]](request: String): Stream[F, Response] =
        for {
          client <- safeClient
          out <- Stream.eval(client.expect[Response](request))
        } yield out

      def post[T, Response: EntityDecoder[F, ?]](request: String, put: T)(
        implicit w: EntityEncoder[F, T]
      ): Stream[F, Response] = {

        val req = Uri.fromString(request) match {
          case Right(uri) =>
            Stream.emit(
              Request()
                .withMethod(Method.POST)
                .withUri(uri)
                .withBody(put)(implicitly[Monad[F]], w)
            )

          case Left(err) => Stream.raiseError[F[Request[F]]](throw err)
        }

        for {
          client <- safeClient
          req <- req.covary[F]
          out <- Stream.eval(client.expect[Response](req))
        } yield out
      }
    }
}
