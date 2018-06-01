package backend

import cats.{Functor, MonadError}
import cats.effect.Sync
import doobie.Transactor
import doobie.util.meta.Meta

package object storage {

  /**
    * Creates a database.
    */
  def database[F[_]: MonadError[?[_], Throwable]](xa: Transactor[F]): Database[F] =
    Database.doobie(xa)

  /**
    * Creates a non-persistent key-value store.
    */
  def keyValueStore[F[_]: Sync, K, V]: KVStore[F, K, V] = KVStore.mutableMap

  /**
    * Creates a persistent key-value store.
    */
  def persistentKeyValueStore[F[_]: Functor, K: Meta, V: Meta](db: Database[F]): KVStore[F, K, V] =
    KVStore.database(db)
}
