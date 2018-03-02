trait Users[F[_], User] {
  def addUser(name: String, password: String): F[Option[User]]
  def removeUser(user: User): F[Unit]
  def searchUser(name: String): F[List[User]]
}

object Users extends UsersInstances {
  def apply[F[_], User](implicit U: Users[F, User]): Users[F, User] = U
}

sealed abstract class UsersInstances {
  implicit def syncedUsers[F[_], User](
      db: Database[F, String]): Users[F, User] = new Users[F, User] {
    def addUser(name: String, password: String): F[User] = ???
    
    def removeUser(user: User): F[Unit] = ???

    def searchUser(name: String): F[List[User]] = db.query[User](???)
  }
}