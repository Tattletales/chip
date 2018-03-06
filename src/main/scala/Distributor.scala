import cats.Applicative
import io.circe.Encoder
import io.circe.syntax._
import org.http4s.circe._
import org.http4s.dsl.io._

trait Distributor[F[_], Message] {
  def share(m: Message): F[Unit]
}

object Distributor extends DistributorInstances {
  implicit def apply[F[_], Message](
      implicit D: Distributor[F, Message]
  ): Distributor[F, Message] =
    D
}

sealed abstract class DistributorInstances {
  implicit def gossip[F[_], G[_]: Applicative, Message: Encoder](
      uri: String,
      httpClient: HttpClient[F, G]
  ): Distributor[F, Message] = new Distributor[F, Message] {
    def share(m: Message): F[Unit] = httpClient.unsafePostAndIgnore(uri, m.asJson)
  }
}
