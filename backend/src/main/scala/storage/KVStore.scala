package backend
package storage

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
  def keys: F[Set[K]]
  def remove(k: K): F[Unit]
}

object KVStore {

  /* ------ Interpreters ------ */

  /**
    * Interpreter to a [[Database]]
    */
  def database[F[_]: Functor, K: Meta, V: Composite: Meta](db: Database[F]): KVStore[F, K, V] =
    new KVStore[F, K, V] {
      def get(k: K): F[Option[V]] = db.query(sql"""
           SELECT value
           FROM kv_store
           WHERE key = $k
         """.stripMargin).map(_.headOption)

      def put(k: K, v: V): F[Unit] = db.insert(update(k, v))

      def keys: F[Set[K]] = db.query[K](sql"""
             SELECT key
             FROM kv_store
           """).map(_.toSet)

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
  def mutableMap[F[_], K, V](implicit F: Sync[F]): KVStore[F, K, V] = new KVStore[F, K, V] {
    private val map = mutable.Map.empty[K, V]

    def get(k: K): F[Option[V]] = F.delay(map.get(k))

    def put(k: K, v: V): F[Unit] = F.delay(map.put(k, v)).map(_ => ())

    def keys: F[Set[K]] = F.delay(map.keySet.toSet)

    def remove(k: K): F[Unit] = F.delay(map.remove(k)).map(_ => ())
  }
}
