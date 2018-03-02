import cats.Monad
import cats.data.OptionT
import cats.implicits._

trait Users[F[_], User] {
  def addUser(name: String, password: String): F[Option[User]]
  def removeUser(user: User): F[Unit]
  def searchUser(name: String): F[List[User]]
}

object Users extends UsersInstances {
  def apply[F[_], User](implicit U: Users[F, User]): Users[F, User] = U
}

sealed abstract class UsersInstances {
  implicit def syncedUsers[F[_]: Monad, User, ](
      db: Database[F, String],
      client: HttpClient[F, String]): Users[F, User] = new Users[F, User] {
    def addUser(name: String, password: String): F[Option[User]] =
      (for {
        user <- OptionT(
          db.insertAndGet[User](
            s"""
           |INSERT INTO users (name, password)
           |VALUES ($name, $password)
       """.stripMargin,
            Seq("id", "user", "password"): _*))

        _ <- client.post(???, ???)
      } yield user).value

    def removeUser(user: User): F[Unit] = ???

    def searchUser(name: String): F[List[User]] = db.query[User](???)
  }
}
