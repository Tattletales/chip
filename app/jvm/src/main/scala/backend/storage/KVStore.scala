package backend.storage

import cats.{Applicative, ApplicativeError, Functor, MonadError}
import cats.data.State
import cats.effect.{Effect, Sync}
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
  def get(k: K): F[V]
  def put(k: K, v: V): F[Unit]
  def put_*(kv: (K, V), kvs: (K, V)*): F[Unit]
  def remove(k: K): F[Unit]
}

object KVStore {

  /* ------ Interpreters ------ */

  /**
    * Interpreter to a [[Database]]
    */
  def dbKVS[F[_]: Functor, K: Meta, V: Composite: Meta](db: Database[F])(
      implicit F: MonadError[F, Throwable]): KVStore[F, K, V] =
    new KVStore[F, K, V] {
      def get(k: K): F[V] = db.query(sql"""
           SELECT value
           FROM kv_store
           WHERE key = $k
         """.stripMargin).flatMap(res => F.fromOption(res.headOption, KeyNotFound(k)))

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

  /**
    * Interpreter to a [[mutable.HashMap]].
    *
    * All the side-effecting calls to the mutable map
    * are suspended/delayed in `F` so they do not escape
    * `F` and can be sequenced appropriately.
    */
  def mapKVS[F[_], K, V](implicit F: Sync[F]): KVStore[F, K, V] = new KVStore[F, K, V] {
    val map = mutable.HashMap.empty[K, V]

    def get(k: K): F[V] =
      F.suspend(F.fromOption(map.get(k), KeyNotFound(k)))

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

  //def stateKVS[K, V]: KVStore[State[Map[K, V], ?], K, V] =
  //  new KVStore[State[Map[K, V], ?], K, V] {
  //    def get(k: K): State[Map[K, V], Option[V]] =
  //      ApplicativeError[State[Map[K, V], ?], Throwable].fromOption(State.inspect(_.get(k)), )
  //    def put(k: K, v: V): State[Map[K, V], Unit] = State.modify(_ + (k -> v))
  //    def put_*(kv: (K, V), kvs: (K, V)*): State[Map[K, V], Unit] =
  //      kvs.foldLeft(put(kv._1, kv._2)) {
  //        case (state, (k, v)) => state.modify(_ + (k -> v))
  //      }
  //    def remove(k: K): State[Map[K, V], Unit] = State.modify(_ - k)
  //  }

  /* ------ Errors ------ */

  sealed trait KVStoreError extends Throwable
  case class KeyNotFound[K](k: K) extends KVStoreError
}
