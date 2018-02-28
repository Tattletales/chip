trait Users[F[_], User, Signup] {
  def addUser(signup: Signup): F[Unit] // Option?
  def removeUser(user: User): F[Unit]
  def searchUser(name: String): F[List[User]]
}

object Users extends UsersInstances {
  def apply[F[_], User, Signup](
      implicit U: Users[F, User, Signup]): Users[F, User, Signup] = U
}

sealed abstract class UsersInstances {}
