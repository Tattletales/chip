object Network {
  trait Alg[F[_], Data] {
    def send(d: Data): F[Unit]
    def receive: F[Data]
  }
}
