package backend.storage

import cats.Functor
import cats.effect.Sync
import cats.implicits._
import doobie.implicits._
import doobie.util.composite.Composite
import doobie.util.fragment.Fragment
import doobie.util.meta.Meta

import scala.collection.mutable

/**
  * Key-value store DSL
  */
trait KVStore[F[_], K, V] {
  def get(k: K): F[Option[V]]
  def put(k: K, v: V): F[Unit]
  def put_*(kv: (K, V), kvs: (K, V)*): F[Unit]
  def keys: F[Set[K]]
  def remove(k: K): F[Unit]
}

object KVStore {

  /* ------ Interpreters ------ */

  /**
    * Interpreter to a [[Database]]
    */
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

      def keys: F[Set[K]] = ???

      def remove(k: K): F[Unit] = db.insert(sql"DELETE FROM kv_store WHERE key = $k")

      private def update(k: K, v: V): Fragment = sql"""
        UPDATE kv_store
        SET value = $v
        WHERE key = $k
      """.stripMargin
    }

  /**
    * Interpreter to a [[mutable.HashMap]].
    *
    * All the side-effecting calls to the mutable map
    * are suspended/delayed in `F` so they do not escape
    * `F` and can be sequenced appropriately.
    */
  def mapKVS[F[_], K, V](implicit F: Sync[F]): KVStore[F, K, V] = new KVStore[F, K, V] {
    val map = mutable.HashMap.empty[K, V]

    def get(k: K): F[Option[V]] = F.delay(map.get(k))

    def put(k: K, v: V): F[Unit] = F.delay(map.put(k, v))

    def put_*(kv: (K, V), kvs: (K, V)*): F[Unit] =
      F.delay {
        (kv +: kvs).foreach {
          case (k, v) => map.put(k, v)
        }
      }

    def keys: F[Set[K]] = F.delay(map.keySet.toSet)

    def remove(k: K): F[Unit] = F.delay(map.remove(k))
  }
}
