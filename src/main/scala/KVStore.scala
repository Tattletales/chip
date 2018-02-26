import cats.data.State

trait KVStore[F[_], K, V] {
  def get(k: K): F[Option[V]]
  def put(k: K, v: V): F[Unit]
  def remove(k: K): F[Unit]
}

object KVStore extends KVStoreInstances {
  def apply[F[_], K, V](implicit K: KVStore[F, K, V]): KVStore[F, K, V] = K
}

sealed abstract class KVStoreInstances {
  implicit def stateKVS[K, V]: KVStore[State[Map[K, V], ?], K, V] =
    new KVStore[State[Map[K, V], ?], K, V] {
      def get(k: K): State[Map[K, V], Option[V]] = State.inspect(_.get(k))
      def put(k: K, v: V): State[Map[K, V], Unit] = State.modify(_ + (k -> v))
      def remove(k: K): State[Map[K, V], Unit] = State.modify(_ - k)
    }
}
