package vault.api

import java.io.File
import java.time._

import cats.Applicative
import cats.data.{Kleisli, NonEmptyList, OptionT}
import cats.effect.Effect
import cats.implicits._
import fs2.Stream
import fs2.StreamApp.ExitCode
import backend.implicits._
import backend.gossip.GossipDaemon
import backend.gossip.model.Node.{NodeId, NodeIdTag}
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
import scalatags.Text.all.{body, form, _}
import vault.model.Account.{Money, MoneyTag}
import vault.model.Accounts

import scala.concurrent.ExecutionContext.Implicits.global

trait Server[F[_]] extends Http4sDsl[F] {
  val run: Stream[F, ExitCode]
}

object Server {
  def authed[F[_]: Effect: EntityEncoder[?[_], F[Json]]](accounts: Accounts[F],
                                                         daemon: GossipDaemon[F]): Server[F] =
    new Server[F] {
      private val amountFieldId = "send-money-input-id"
      private val beneficiaryFieldId = "beneficiary-input-id"

      private def homeForm(balance: Money) =
        div(
          id := "vault-main-block",
          div(
            id := "account-container",
            `class` := "container"
          ),
          h1(
            id := "account-balance",
            s"Current balance: $balance"
          ),
          form(
            action := "/balance",
            method := "GET",
            id := "account-balance-form",
            `class` := "pure-form pure-form-aligned",
            div(
              `class` := "pure-controls",
              button(
                `type` := "submit",
                `class` := "pure-button pure-button-primary",
                "Check my account balance"
              )
            )
          ),
          hr,
          h1("Issue a transfer"),
          form(
            action := "/transfer",
            method := "POST",
            id := "account-transfer-form",
            `class` := "pure-form pure-form-aligned",
            div(
              `class` := "pure-control-group",
              label(`for` := amountFieldId, `class` := "", "Transfer "),
              input(
                `type` := "text",
                `class` := "",
                name := amountFieldId,
                id := amountFieldId,
                placeholder := "Insert amount"
              )
            ),
            div(
              `class` := "pure-control-group",
              label(`for` := beneficiaryFieldId, `class` := "", " to "),
              input(
                `type` := "text",
                `class` := "",
                name := beneficiaryFieldId,
                id := beneficiaryFieldId,
                placeholder := "Insert beneficiary"
              )
            ),
            div(
              `class` := "pure-controls",
              button(
                `type` := "submit",
                `class` := "pure-button pure-button-primary",
                "Send money"
              )
            )
          )
        )

      private def homePage(balance: Money) =
        html(
          head(
            meta(
              charset := "UTF-8",
              httpEquiv := "Content-Type",
              content := "text/html"
            ),
            title := "With Vault, your money is secure !",
            link(
              rel := "stylesheet",
              href := "https://unpkg.com/purecss@1.0.0/build/pure-min.css"
            )
          ),
          body(
            div(
              id := "app-contents",
              p(homeForm(balance))
            ) /*,
            script(
              `type` := "text/javascript",
              src := "js/app-jsdeps.js"
            ),
            script(
              `type` := "text/javascript",
              src := "js/app-fastopt.js"
            )*/
          )
        ).render

      private def balancePage(balance: Money) =
        html(
          head(
            meta(
              charset := "UTF-8",
              httpEquiv := "Content-Type",
              content := "text/html"
            ),
            title := "With Vault, your money is secure !",
            link(
              rel := "stylesheet",
              href := "https://unpkg.com/purecss@1.0.0/build/pure-min.css"
            )
          ),
          body(
            div(
              id := "app-contents",
              p(s"Your current account balance is : $balance"),
              p(a(href := "/", "Go back to home page"))
            )
          )
        ).render

      private def transferPage(to: NodeId, amount: Money) =
        html(
          head(
            meta(
              charset := "UTF-8",
              httpEquiv := "Content-Type",
              content := "text/html"
            ),
            title := "With Vault, your money is secure !",
            link(
              rel := "stylesheet",
              href := "https://unpkg.com/purecss@1.0.0/build/pure-min.css"
            )
          ),
          body(
            div(
              id := "app-contents",
              p(s"Maybe the transfer of $amount to $to was successful. ",
                a(href := "/balance", "Check your balance to make sure")),
              p(a(href := "/", "Go back to home page"))
            )
          )
        ).render

      private def okResp(res: String) =
        Ok(res).map(_.withContentType(`Content-Type`(`text/html`, Charset.`UTF-8`)))

      private val service: HttpService[F] = HttpService {
        case GET -> Root =>
          for {
            id <- daemon.getNodeId
            balance <- accounts.balance(id)
            response <- okResp(homePage(balance))
          } yield response

        case GET -> Root / "balance" =>
          for {
            id <- daemon.getNodeId
            balance <- accounts.balance(id)
            response <- okResp(balancePage(balance))
          } yield response

        case req @ POST -> Root / "transfer" =>
          req.decode[UrlForm] { data =>
            val to = tag[NodeIdTag][String](
              data.values.get(beneficiaryFieldId).map(_.foldRight("")(_ + _)).getOrElse(""))
            val amount = tag[MoneyTag][Double](
              data.values.get(amountFieldId).map(_.foldRight("")(_ + _)).getOrElse("0.0").toDouble)

            for {
              _ <- accounts.transfer(to, amount)
              response <- okResp(transferPage(to, amount))
            } yield response
          }

        case GET -> Root / "hello" / name =>
          Ok(s"Hello $name.").map(_.withContentType(`Content-Type`(`text/html`, Charset.`UTF-8`)))

        case request @ GET -> Root / "js" / file ~ "js" =>
          StaticFile
            .fromFile(new File(s"app/js/target/scala-2.12/$file.js"), Some(request))
            .getOrElseF(NotFound())
      }

      val run: Stream[F, ExitCode] = {
        BlazeBuilder[F]
          .bindHttp(8080, "localhost")
          .mountService(service, "/")
          .serve
      }
    }
}
