//trait Network[F[_], Data] {
//  def send(d: Data): F[Unit]
//  def receive: F[Data]
//}

class NetworkProvider[Data] {
  trait NetworkAlg[F[_]] {
    def send(d: Data): F[Unit]
    def receive: F[Data]
  }
}

object Network {
  def apply[Data] = new NetworkProvider[Data]
  
  class SimpleNet[Data]() extends NetworkProvider[Data].N
}
