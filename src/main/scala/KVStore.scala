trait KVStore[F[_], K, V] {
  def get(k: K): F[Option[V]]
  def put(k: K, v: V): F[Unit]
  def remove(k: K): F[Unit]
}