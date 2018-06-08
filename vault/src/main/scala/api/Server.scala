package vault.api

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
import vault.events.TransactionStage
import vault.model.Money
import vault.model.Accounts

import scala.concurrent.ExecutionContext.Implicits.global

object Server {
  def apply[F[_]: Effect: EntityEncoder[?[_], F[Json]], E](
      accounts: Accounts[F],
      daemon: GossipDaemon[F, TransactionStage, E],
      port: Int): Program[F, ExitCode] =
    new Program[F, ExitCode] with Http4sDsl[F] {

      private def okResp(res: String) =
        Ok(res).map(_.withContentType(`Content-Type`(`text/html`, Charset.`UTF-8`)))

      private val service: HttpService[F] = HttpService {
        case GET -> Root =>
          for {
            id <- daemon.getNodeId
            balance <- accounts.balance(id)
            response <- okResp(Frontend.homePage(balance))
          } yield response

        case GET -> Root / "balance" =>
          for {
            id <- daemon.getNodeId
            balance <- accounts.balance(id)
            response <- okResp(Frontend.balancePage(balance))
          } yield response

        case req @ POST -> Root / "transfer" =>
          req.decode[UrlForm] { data =>
            // TODO: Do not fail silently
            val to = tag[NodeIdTag][String](
              data.values
                .get(Frontend.beneficiaryFieldId)
                .map(_.foldRight("")(_ + _))
                .getOrElse(""))
            val amount = implicitly[ApplicativeError[F, Throwable]].fromEither(
              applyRef[Money](
                data.values
                  .get(Frontend.amountFieldId)
                  .map(_.foldRight("")(_ + _))
                  .getOrElse("0.0")
                  .toDouble)
                .leftMap(_ => new IllegalArgumentException("Transfer amount should be positive.")))

            for {
              amount <- amount
              response <- accounts.transfer(to, amount) *> okResp(Frontend.transferPage(to, amount))
            } yield response
          }

        case GET -> Root / "hello" / name =>
          Ok(s"Hello $name.").map(_.withContentType(`Content-Type`(`text/html`, Charset.`UTF-8`)))

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
