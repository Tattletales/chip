package backend.storage

import cats.{Applicative, Functor}
import cats.data.State
import cats.effect.Effect
import cats.implicits._
import doobie.implicits._
import doobie.util.composite.Composite
import doobie.util.fragment.Fragment
import doobie.util.meta.Meta

trait KVStore[F[_], K, V] {
  def get(k: K): F[Option[V]]
  def put(k: K, v: V): F[Unit]
  def put_*(kv: (K, V), kvs: (K, V)*): F[Unit]
  def remove(k: K): F[Unit]
}

object KVStore {
  def stateKVS[K, V]: KVStore[State[Map[K, V], ?], K, V] =
    new KVStore[State[Map[K, V], ?], K, V] {
      def get(k: K): State[Map[K, V], Option[V]] = State.inspect(_.get(k))
      def put(k: K, v: V): State[Map[K, V], Unit] = State.modify(_ + (k -> v))
      def put_*(kv: (K, V), kvs: (K, V)*): State[Map[K, V], Unit] =
        kvs.foldLeft(put(kv._1, kv._2)) {
          case (state, (k, v)) => state.modify(_ + (k -> v))
        }
      def remove(k: K): State[Map[K, V], Unit] = State.modify(_ - k)
    }

  def dbKVS[F[_]: Functor, K: Meta, V: Composite: Meta](db: Database[F]): KVStore[F, K, V] =
    new KVStore[F, K, V] {
      def get(k: K): F[Option[V]] = db.query(sql"""
           SELECT value
           FROM kv_store
           WHERE key = $k
         """.stripMargin).map(_.headOption)

      def put(k: K, v: V): F[Unit] = put_*((k, v))

      def put_*(x: (K, V), xs: (K, V)*): F[Unit] =
        db.insert(update(x._1, x._2), xs.map { case (k, v) => update(k, v) }: _*)

      def remove(k: K): F[Unit] = db.insert(sql"DELETE FROM kv_store WHERE key = $k")

      private def update(k: K, v: V): Fragment = sql"""
        UPDATE kv_store
        SET value = $v
        WHERE key = $k
      """.stripMargin
    }

  def mapKVS[F[_], K, V](implicit F: Effect[F]): KVStore[F, K, V] = new KVStore[F, K, V] {
    val map = scala.collection.mutable.HashMap.empty[K, V]

    def get(k: K): F[Option[V]] =
      F.delay(map.get(k))

    def put(k: K, v: V): F[Unit] =
      F.delay(map.put(k, v))

    def put_*(kv: (K, V), kvs: (K, V)*): F[Unit] =
      F.delay {
        (kv +: kvs).foreach {
          case (k, v) => map.put(k, v)
        }
      }

    def remove(k: K): F[Unit] = F.delay(map.remove(k))
  }
}
