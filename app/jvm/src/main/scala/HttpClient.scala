import cats.data.OptionT
import cats.effect._
import cats.{Applicative, Monad}
import fs2.Stream
import org.http4s.client.Client
import org.http4s.client.blaze.Http1Client
import org.http4s.{Uri => Http4sUri, _}

trait HttpClient[F[_], G[_]] {
  def get[Response: EntityDecoder[G, ?]](uri: String): F[Response]

  def post[T, Response: EntityDecoder[G, ?]](uri: String, body: T)(
      implicit T: EntityEncoder[G, T]
  ): F[Option[Response]]

  def unsafePost[T, Response: EntityDecoder[G, ?]](uri: String, body: T)(
      implicit T: EntityEncoder[G, T]
  ): F[Response]

  def postAndIgnore[T: EntityEncoder[G, ?]](uri: String, body: T): F[Option[Unit]]

  def unsafePostAndIgnore[T: EntityEncoder[G, ?]](uri: String, body: T): F[Unit]
}

object HttpClient extends HttpClientInstances {
  def apply[F[_], G[_]](implicit H: HttpClient[F, G]): HttpClient[F, G] =
    H
}

sealed abstract class HttpClientInstances {
  implicit def http4sClient[F[_]: Monad: Effect]: HttpClient[Stream[F, ?], F] =
    new HttpClient[Stream[F, ?], F] {
      private[this] val safeClient = Http1Client.stream[F]()

      def get[Response: EntityDecoder[F, ?]](uri: String): Stream[F, Response] =
        for {
          client <- safeClient
          out <- Stream.eval(client.expect[Response](uri))
        } yield out

      def post[T, Response: EntityDecoder[F, ?]](uri: String, body: T)(
          implicit w: EntityEncoder[F, T]
      ): Stream[F, Option[Response]] =
        (for {
          client <- OptionT.liftF(safeClient)
          req <- OptionT(Stream.emit(genPostReq(uri, body).toOption).covary[F])
          out <- OptionT.liftF(Stream.eval(client.expect[Response](req)))
        } yield out).value

      def unsafePost[T, Response: EntityDecoder[F, ?]](uri: String, body: T)(
          implicit w: EntityEncoder[F, T]
      ): Stream[F, Response] = {

        val req = genPostReq(uri, body) match {
          case Right(req) => Stream.emit(req)
          case Left(err) => Stream.raiseError[F[Request[F]]](err)
        }

        for {
          client <- safeClient
          req <- req.covary[F]
          out <- Stream.eval(client.expect[Response](req))
        } yield out
      }

      def postAndIgnore[T: EntityEncoder[F, ?]](uri: String, body: T): Stream[F, Option[Unit]] =
        (for {
          client <- OptionT.liftF(safeClient)
          req <- OptionT(Stream.emit(genPostReq(uri, body).toOption).covary[F])
          out <- OptionT.liftF(
            Stream.eval(client.fetch[Unit](req)(_ => implicitly[Applicative[F]].pure(()))))
        } yield out).value

      def unsafePostAndIgnore[T: EntityEncoder[F, ?]](uri: String, body: T): Stream[F, Unit] = {
        val req = genPostReq(uri, body) match {
          case Right(req) => Stream.emit(req)
          case Left(err) => Stream.raiseError[F[Request[F]]](err)
        }

        for {
          client <- safeClient
          req <- req.covary[F]
          out <- Stream.eval(client.fetch[Unit](req)(_ => implicitly[Applicative[F]].pure(())))
        } yield out
      }

      private def genPostReq[T: EntityEncoder[F, ?]](uri: String, body: T) =
        Http4sUri.fromString(uri).map {
          Request().withMethod(Method.POST).withUri(_).withBody(body)
        }
    }
}