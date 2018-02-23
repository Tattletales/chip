package tagless

import java.util.UUID

import cats._
import cats.data._
import cats.implicits._
import tagless.AccessRights.AccessRightsAlg
import tagless.Authentification.Auth
import tagless.KVStore.KVAlg

object Chip {
  case class Chip(body: String)
  case class User(id: UUID, name: String)

  trait ChipRepoAlg[F[_]] {
    def getChips(user: User): F[List[Chip]]
    def postChip(user: User, chip: Chip): F[Unit] // Auth[User] => F2[Option[Unit]]
  }

  class ChipRepoThroughKV[F[_]: Monad](kv: KVAlg[F, User, List[Chip]]) extends ChipRepoAlg[F] {
    override def getChips(user: User): F[List[Chip]] = kv.get(user).map(_.getOrElse(List.empty))

    override def postChip(user: User, chip: Chip): F[Unit] =
      for {
        chips <- getChips(user)
        _ <- kv.put(user, chip :: chips)
      } yield ()
  }

  //val bla = new ChipRepoThroughKV(new KVStore.StateInterpreter[User, List[Chip]] {})

  type Authed[F[_], R] = ReaderT[F, Auth[User], Option[R]]
  class ChipRepoWithAccessRights[F[_]: Monad](cr: ChipRepoAlg[F], ar: AccessRightsAlg[F])
      extends ChipRepoAlg[Authed[F, ?]] {
    override def getChips(user: User): Authed[F, List[Chip]] = ReaderT { auth =>
      for {
        canRead <- ar.canRead(user, auth)
        chips <- if (canRead) cr.getChips(user).map(Some(_)) else implicitly[Applicative[F]].pure(None)
      } yield chips
    }

    override def postChip(user: User, chip: Chip): Authed[F, Unit] = ReaderT { auth =>
      for {
        ok <- ar.canWrite(user, auth)
        res <- if (ok) cr.postChip(user, chip).map(Some(_))
        else implicitly[Applicative[F]].pure(None)
      } yield res
    }
  }

  //trait StateInterpreter extends ChipRepoAlg[State[Map[User, List[Chip]], ?]] {
  //  override def getChips(user: User): State[Map[User, List[Chip]], List[Chip]] =
  //    State.inspect(_.getOrElse(user, List.empty))
  //
  //  override def postChip(user: User, chip: Chip): State[Map[User, List[Chip]], Option[Unit]] =
  //    State { s =>
  //      (s + (user -> (chip :: s.getOrElse(user, List.empty))), Some(()))
  //    }
  //}
}
