package tagless

import cats.data.State

object KVStore {
  trait KVAlg[F[_], K, V] {
    def get(k: K): F[Option[V]]
    def put(k: K, v: V): F[Unit]
  }

  trait StateInterpreter[K, V] extends KVAlg[State[Map[K, V], ?], K, V] {
    override def get(k: K): State[Map[K, V], Option[V]] = State.inspect { s =>
      s.get(k)
    }

    override def put(k: K, v: V): State[Map[K, V], Unit] = State.modify { s =>
      s + (k -> v)
    }
  }
}
