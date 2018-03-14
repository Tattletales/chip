import java.io.File
import java.time._

import cats.Applicative
import cats.data.{Kleisli, NonEmptyList, OptionT}
import cats.effect.Effect
import cats.implicits._
import fs2.Stream
import fs2.StreamApp.ExitCode
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.CacheDirective._
import org.http4s.MediaType._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.circe._
import org.http4s.headers.{Cookie => _, _}
import org.http4s.server.AuthMiddleware
import org.http4s.server.blaze.BlazeBuilder
import org.reactormonk.{CryptoBits, PrivateKey}
import scalatags.Text.all._
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._

import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.io._

import scala.concurrent.ExecutionContext.Implicits.global

trait Server[F[_]] extends Http4sDsl[F] {
  val run: Stream[F, ExitCode]
}

object Server {
  def authed[F[_]: Effect: EntityEncoder[?[_], F[Json]]](users: Users[F],
                                                         tweets: Tweets[F],
                                                         daemon: GossipDaemon[F]): Server[F] =
    new Server[F] {
      private val key = PrivateKey(
        scala.io.Codec.toUTF8(scala.util.Random.alphanumeric.take(20).mkString("")))
      private val crypto = CryptoBits(key)
      private val clock = Clock.systemUTC

      val page = {
        html(
          head(
            meta(
              charset := "UTF-8",
              title := "Chip chip!",
              link(
                rel := "stylesheet",
                href := "https://unpkg.com/purecss@1.0.0/build/pure-min.css"
              )
            )
          ),
          body(
            div(
              id := "app-contents"
            ),
            script(
              `type` := "text/javascript",
              src := "js/app-jsdeps.js"
            ),
            script(
              `type` := "text/javascript",
              src := "js/app-fastopt.js"
            )
          )
        ).render
      }

      private val login: HttpService[F] = HttpService {
        case GET -> Root / "login" / userName =>
          for {
            id <- daemon.getUniqueId
            user <- users.getUser(id).flatMap {
              case Some(user) => implicitly[Applicative[F]].pure(user)
              case None       => users.addUser(userName)
            }
            message = crypto.signToken(user.id, clock.millis.toString)
            response <- Ok("Logged in!".asJson).map(_.addCookie(Cookie("authcookie", message, path = Some("/"))))
          } yield response
      }

      private def retrieveUser: Kleisli[F, String, Either[String, User]] =
        Kleisli { id =>
          users.getUser(id).map(_.toRight(s"Could not retrieve user with id $id"))
        }

      private val authUser: Kleisli[F, Request[F], Either[String, User]] = Kleisli { request =>
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

      private val onFailure: AuthedService[String, F] = Kleisli { request =>
        OptionT.liftF(Forbidden(request.authInfo))
      }

      private val middleware: AuthMiddleware[F, User] = AuthMiddleware(authUser, onFailure)

      private val read: HttpService[F] = HttpService {
        case GET -> Root =>
          Ok(page).map(
            _.withContentType(`Content-Type`(`text/html`, Charset.`UTF-8`))
              .putHeaders(`Cache-Control`(NonEmptyList.of(`no-cache`())))
          )
        case GET -> Root / "getTweets" / userName =>
          val response = for {
            user <- users.searchUser(userName).map(_.head)
            postedTweets <- tweets.getTweets(user)
          } yield postedTweets

          Ok(response.map(_.asJson))

        case GET -> Root / "getAllTweets" => Ok(tweets.getAllTweets.map(_.asJson))

        case request @ GET -> Root / "js" / file ~ "js" =>
          StaticFile
            .fromFile(new File(s"app/js/target/scala-2.12/$file.js"), Some(request))
            .getOrElseF(NotFound())
      }

      private val write: AuthedService[User, F] = AuthedService {
        case authedReq @ POST -> Root / "postTweet" as user =>
          authedReq.req.as[String].flatMap { body =>

            val f = tweets.addTweet(user, body)

            Ok(f.map(_.asJson))
          }
      }

      private val service: HttpService[F] = read <+> login <+> middleware(write)

      val run: Stream[F, ExitCode] = {
        BlazeBuilder[F]
          .bindHttp(8080, "localhost")
          .mountService(service, "/")
          .serve
      }
    }
}
