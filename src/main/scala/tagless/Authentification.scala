package tagless

object Authentification {
  case class Auth[U](u: U)

  trait AuthAlg[F[_]] {
    def authenticate[U](u: U): F[Auth[U]]
  }
}