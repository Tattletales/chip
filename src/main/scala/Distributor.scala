import HttpClient.Uri
import org.http4s.EntityEncoder

trait Distributor[F[_], Message] {
  def share(m: Message): F[Unit]
}

object Distributor extends DistributorInstances {
  implicit def apply[F[_], Message](implicit D: Distributor[F, Message]): Distributor[F, Message] =
    D
}

sealed abstract class DistributorInstances {
  implicit def gossip[F[_], Message: EntityEncoder[F, ?]](
    uri: Uri,
    httpClient: HttpClient[F, F]
  ): Distributor[F, Message] = new Distributor[F, Message] {
    def share(m: Message): F[Unit] = httpClient.postAndIgnore(uri, m)
  }
}
