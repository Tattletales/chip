import cats.{Applicative, Monad}
import cats.effect._
import fs2.Stream
import org.http4s._
import org.http4s.client.blaze.Http1Client

trait HttpClient[F[_], G[_], Request] {
  def get[Response: EntityDecoder[G, ?]](request: Request): F[Response]
  def post[T, Response: EntityDecoder[G, ?]](request: Request, put: T)(
    implicit T: EntityEncoder[G, T]
  ): F[Response]
  def postAndIgnore[T: EntityEncoder[G, ?]](request: Request, put: T): F[Unit]
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

        val req = genPostReq(request, put) match {
          case Right(req) => Stream.emit(req)
          case Left(err)  => Stream.raiseError[F[Request[F]]](err)
        }

        for {
          client <- safeClient
          req <- req.covary[F]
          out <- Stream.eval(client.expect[Response](req))
        } yield out
      }

      def postAndIgnore[T: EntityEncoder[F, ?]](request: String, put: T): Stream[F, Unit] = {
        val req = genPostReq(request, put) match {
          case Right(req) => Stream.emit(req)
          case Left(err)  => Stream.raiseError[F[Request[F]]](err)
        }

        for {
          client <- safeClient
          req <- req.covary[F]
          out <- Stream.eval(client.fetch[Unit](req)(_ => implicitly[Applicative[F]].pure(())))
        } yield out
      }

      private def genPostReq[T: EntityEncoder[F, ?]](uri: String, body: T) =
        Uri.fromString(uri).map {
          Request().withMethod(Method.POST).withUri(_).withBody(body)
        }
    }
}
