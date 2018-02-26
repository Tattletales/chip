object Users {
  trait Alg[F[_], User, Signup] {
    def addUser(signup: Signup): F[Unit] // Option?
    def removeUser(user: User): F[Unit]
    def searchUser(name: String): F[List[User]]
  }
}
