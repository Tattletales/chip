package vault
package api

import java.io.File

import cats.effect.Effect
import cats.implicits._
import fs2.Stream
import fs2.StreamApp.ExitCode
import backend.gossip.GossipDaemon
import backend.gossip.Node.NodeIdTag
import cats.ApplicativeError
import eu.timepit.refined.api.RefType.applyRef
import io.circe._
import org.http4s.MediaType._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Cookie => _, _}
import org.http4s.server.blaze.BlazeBuilder
import backend.programs.Program
import shapeless.tag
import scalatags.Text.all._
import events.TransactionStage
import model.Money
import model.Accounts

import scala.concurrent.ExecutionContext.Implicits.global

object Server {
  def apply[F[_]: Effect: EntityEncoder[?[_], F[Json]], E](
      accounts: Accounts[F],
      daemon: GossipDaemon[F, TransactionStage, E],
      port: Int): Program[F, ExitCode] =
    new Program[F, ExitCode] with Http4sDsl[F] {

      private def okResp(res: String) =
        Ok(res).map(_.withContentType(`Content-Type`(`text/html`, Charset.`UTF-8`)))

      private def decodeField(form: UrlForm, fieldName: String, default: String): String =
        form.values.get(fieldName).map(_.foldRight("")(_ + _)).getOrElse(default)

      private val service: HttpService[F] = HttpService {
        case GET -> Root =>
          for {
            id <- daemon.getNodeId
            balance <- accounts.balance(id)
            response <- okResp(Frontend.homePage(id, balance))
          } yield response

        case GET -> Root / "balance" =>
          for {
            id <- daemon.getNodeId
            balance <- accounts.balance(id)
            response <- okResp(Frontend.balancePage(id, balance))
          } yield response

        case req @ POST -> Root / "balance" =>
          req.decode[UrlForm] { data =>
            val nodeId =
              tag[NodeIdTag][String](decodeField(data, Frontend.checkBalanceForFieldId, ""))

            for {
              balance <- accounts.balance(nodeId)
              response <- okResp(Frontend.balancePage(nodeId, balance))
            } yield response
          }

        case req @ POST -> Root / "transfer" =>
          req.decode[UrlForm] { data =>
            // TODO: Do not fail silently
            val to = tag[NodeIdTag][String](decodeField(data, Frontend.beneficiaryFieldId, ""))
            val amount = implicitly[ApplicativeError[F, Throwable]].fromEither(
              applyRef[Money](decodeField(data, Frontend.amountFieldId, "0.0").toDouble)
                .leftMap(_ => new IllegalArgumentException("Transfer amount should be positive.")))

            for {
              amount <- amount
              response <- accounts.transfer(to, amount) *> okResp(Frontend.transferPage(to, amount))
            } yield response
          }

        case request @ GET -> Root / "js" / file ~ "js" =>
          StaticFile
            .fromFile(new File(s"app/js/target/scala-2.12/$file.js"), Some(request))
            .getOrElseF(NotFound())
      }

      def run: Stream[F, ExitCode] =
        BlazeBuilder[F]
          .bindHttp(port, "localhost")
          .withWebSockets(true)
          .mountService(service, "/")
          .serve
    }
}
