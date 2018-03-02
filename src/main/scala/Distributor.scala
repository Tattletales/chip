trait Distributor[F[_], Message] {
  def share(m: Message): F[Unit]
}

object Distributor extends DistributorInstances {
  implicit def apply[F[_], Message](
      implicit D: Distributor[F, Message]): Distributor[F, Message] = D
}

sealed abstract class DistributorInstances {}
