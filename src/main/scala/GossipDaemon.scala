import io.circe.{Encoder, Json}
import org.http4s.{EntityDecoder, EntityEncoder}
import io.circe.syntax._

trait GossipDaemon[F[_]] {
  def getUniqueId: F[String]
  def send[Message: Encoder](m: Message): F[Unit]
}

object GossipDaemon extends GossipDaemonInstances {
  def apply[F[_]](implicit D: GossipDaemon[F]): GossipDaemon[F] = D
}

sealed abstract class GossipDaemonInstances {
  implicit def localhost[F[_], G[_]: EntityDecoder[?[_], String]: EntityEncoder[?[_], Json]](
      httpClient: HttpClient[F, G]): GossipDaemon[F] =
    new GossipDaemon[F] {
      private val root = "localhost:2018"

      def getUniqueId: F[String] = httpClient.get[String](s"$root/unique")

      def send[Message: Encoder](m: Message): F[Unit] = httpClient.unsafePostAndIgnore(s"$root/gossip", m.asJson)
    }
}
