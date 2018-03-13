import java.time._

import cats.data.{Kleisli, OptionT}
import cats.effect.Effect
import cats.implicits._
import fs2.Stream
import fs2.StreamApp.ExitCode
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.server.AuthMiddleware
import org.http4s.server.blaze.BlazeBuilder
import org.reactormonk.{CryptoBits, PrivateKey}

import scala.concurrent.ExecutionContext.Implicits.global

trait Server[F[_]] extends Http4sDsl[F] {
  val server: Stream[F, ExitCode]
}

object Server {
  def simple[F[_]: Effect: EntityEncoder[?[_], F[Json]]](users: Users[F],
                                                         tweets: Tweets[F]): Server[F] =
    new Server[F] {
      private val key = PrivateKey(
        scala.io.Codec.toUTF8(scala.util.Random.alphanumeric.take(20).mkString("")))
      private val crypto = CryptoBits(key)
      private val clock = Clock.systemUTC

      def verifyLogin(r: Request[F]): F[Either[String, User]] = ???

      val logIn: Kleisli[F, Request[F], Response[F]] = Kleisli { request =>
        verifyLogin(request).flatMap {
          case Left(error) => Forbidden(error)
          case Right(user) =>
            val message = crypto.signToken(user.id, clock.millis.toString)
            Ok("Logged in!").map(_.addCookie(Cookie("authcookie", message)))
        }
      }

      def retrieveUser: Kleisli[F, String, Either[String, User]] =
        Kleisli { id =>
          users.getUser(id).map(_.toRight(s"Could not retrieve user with id $id"))
        }

      val authUser: Kleisli[F, Request[F], Either[String, User]] = Kleisli { request =>
        val message = for {
          header <- headers.Cookie.from(request.headers).toRight("Cookie parsing error")
          cookie <- header.values.toList
            .find(_.name == "authcookie")
            .toRight("Couldn't find the authcookie")
          token <- crypto.validateSignedToken(cookie.content).toRight("Cookie invalid")
          message <- Either.catchOnly[NumberFormatException](token).leftMap(_.toString)
        } yield message
        message.traverse(retrieveUser.run).map(_.fold(Left(_), e => e))
      }

      val onFailure: AuthedService[String, F] = Kleisli { request =>
        OptionT.liftF(Forbidden(request.authInfo))
      }

      val middleware: AuthMiddleware[F, User] = AuthMiddleware(authUser, onFailure)

      val read: HttpService[F] = HttpService {
        case GET -> Root / "getTweets" / userName =>
          val response = for {
            user <- users.searchUser(userName).map(_.head)
            postedTweets <- tweets.getTweets(user)
          } yield postedTweets

          Ok(response.map(_.asJson))
      }

      val write: AuthedService[User, F] = AuthedService {
        case PUT -> Root / "postTweet" / body as user =>
          val f = tweets.addTweet(user, body)

          Ok(f.map(_.asJson))
      }

      val service: HttpService[F] = middleware(write) <+> read

      val server: Stream[F, ExitCode] = {
        BlazeBuilder[F]
          .bindHttp(8080, "localhost")
          .mountService(service, "/")
          .serve
      }
    }
}
