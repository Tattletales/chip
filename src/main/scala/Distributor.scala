import HttpClient.Uri
import org.http4s.EntityEncoder

trait Distributor[F[_], G[_], Message] {
  def share(m: Message): F[Unit]
}

object Distributor extends DistributorInstances {
  implicit def apply[F[_], G[_], Message](implicit D: Distributor[F, G, Message]): Distributor[F, G, Message] =
    D
}

sealed abstract class DistributorInstances {
  implicit def gossip[F[_], G[_], Message: EntityEncoder[G, ?]](
    uri: Uri,
    httpClient: HttpClient[F, G]
  ): Distributor[F, G, Message] = new Distributor[F, G, Message] {
    def share(m: Message): F[Unit] = httpClient.postAndIgnore(uri, m)
  }
}
