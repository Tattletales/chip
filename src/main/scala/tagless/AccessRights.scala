package tagless

import cats._
import cats.implicits._
import tagless.Authentification.{Auth, AuthAlg}
import tagless.Chip.User
import tagless.KVStore.KVAlg

object AccessRights {
  case class ReadRights(users: List[User])

  trait AccessRightsAlg[F[_]] {
    def canRead(user: User, auth: Auth[User]): F[Boolean]
    def canWrite(user: User, auth: Auth[User]): F[Boolean]
  }

  class AccessRightsThroughAuthAndAccessRightsRepo[F[_]: Functor](
      authenticator: AuthAlg[F],
      accessRightsRepo: KVAlg[F, Auth[User], ReadRights])
      extends AccessRightsAlg[F] {
    override def canRead(user: User, auth: Auth[User]): F[Boolean] = {
      accessRightsRepo.get(auth).map {
        case Some(readRights) => readRights.users contains user
        case None => false
      }
    }

    override def canWrite(user: User, auth: Auth[User]): F[Boolean] =
      authenticator.authenticate(user) map (_ == auth)
  }
}
