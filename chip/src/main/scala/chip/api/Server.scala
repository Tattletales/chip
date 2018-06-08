package chip.api

import java.io.File
import java.time._

import cats.Applicative
import cats.data.{Kleisli, NonEmptyList, OptionT}
import cats.effect.Effect
import cats.implicits._
import chip.model.Tweet.ContentTag
import chip.model.User.UsernameTag
import chip.implicits._
import chip.model.{Tweets, User, Users}
import fs2.Stream
import fs2.StreamApp.ExitCode
import backend.implicits._
import backend.gossip.GossipDaemon
import backend.gossip.Node.{NodeId, NodeIdTag}
import chip.events.ReplicateEvents.Event
import io.circe._
import io.circe.generic.auto._
import io.circe.syntax._
import org.http4s.CacheDirective._
import org.http4s.MediaType._
import org.http4s._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Cookie => _, _}
import org.http4s.server.AuthMiddleware
import org.http4s.server.blaze.BlazeBuilder
import org.reactormonk.{CryptoBits, PrivateKey}
import shapeless.tag
import scalatags.Text.all._

import scala.concurrent.ExecutionContext.Implicits.global

trait Server[F[_]] extends Http4sDsl[F] {
  val run: Stream[F, ExitCode]
}

object Server {
  def authed[F[_]: Effect: EntityEncoder[?[_], F[Json]], E](
      users: Users[F],
      tweets: Tweets[F],
      daemon: GossipDaemon[F, Event, E],
      port: Int): Server[F] =
    new Server[F] {
      private val key = PrivateKey(
        scala.io.Codec.toUTF8(scala.util.Random.alphanumeric.take(20).mkString("")))
      private val crypto = CryptoBits(key)
      private val clock = Clock.systemUTC

      private def okResp(res: String) =
        Ok(res).map(_.withContentType(`Content-Type`(`text/html`, Charset.`UTF-8`)))

      private val login: HttpService[F] = HttpService {
        case req @ POST -> Root / "login" =>
          req.decode[UrlForm] { data =>
            val userName =
              data.values.get(Frontend.usrInputId).map(_.foldRight("")(_ + _)).getOrElse("")

            for {
              id <- daemon.getNodeId
              user <- users.getUser(id).flatMap {
                case Some(user) => implicitly[Applicative[F]].pure(user)
                case None       => users.addUser(tag[UsernameTag][String](userName))
              }
              message = crypto.signToken(user.id, clock.millis.toString)
              response <- Ok(Frontend.renderLoggedInPage)
                .map(_.withContentType(`Content-Type`(`text/html`, Charset.`UTF-8`)))
                .map(_.addCookie(Cookie("authcookie", message, path = Some("/"))))
            } yield response
          }
      }

      private def retrieveUser: Kleisli[F, NodeId, Either[String, User]] =
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
          message <- Either
            .catchOnly[NumberFormatException](token)
            .leftMap(_.toString)
            .map(tag[NodeIdTag][String])
        } yield message

        message.traverse(retrieveUser.run).map(_.fold(Left(_), e => e))
      }

      private val onFailure: AuthedService[String, F] =
        Kleisli[OptionT[F, ?], AuthedRequest[F, String], Response[F]] { request =>
          OptionT.liftF(Forbidden(request.authInfo))
        }

      private val middleware: AuthMiddleware[F, User] =
        AuthMiddleware[F, String, User](authUser, onFailure)

      private val read: HttpService[F] = HttpService {
        case GET -> Root =>
          okResp(Frontend.renderLoginForm).map(
            _.putHeaders(`Cache-Control`(NonEmptyList.of(`no-cache`())))
          )

        case GET -> Root / "chip" =>
          for {
            tweets <- tweets.getAllTweets
            response <- okResp(Frontend.renderTweetingForm(tweets))
          } yield response
        case GET -> Root / "getTweets" / userName =>
          val response = for {
            user <- users.searchUser(tag[UsernameTag][String](userName)).map(_.head)
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
          authedReq.req.decode[UrlForm] { data =>
            val body = tag[ContentTag][String](
              data.values.get(Frontend.chipInputFieldId).map(_.foldRight("")(_ + _)).getOrElse(""))

            if (body.nonEmpty) {
              for {
                _ <- tweets.addTweet(user, body)
                tweets <- tweets.getAllTweets
                response <- okResp(Frontend.renderTweetingForm(tweets))
              } yield response
            } else {
              for {
                tweets <- tweets.getAllTweets
                response <- okResp(
                  Frontend.renderTweetingForm(tweets, Some("Cannot chip an empty message.")))
              } yield response
            }
          }
      }

      private val service: HttpService[F] = read <+> login <+> middleware(write)

      val run: Stream[F, ExitCode] = {
        BlazeBuilder[F]
          .bindHttp(port, "localhost")
          .mountService(service, "/")
          .serve
      }
    }
}
