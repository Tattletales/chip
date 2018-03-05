trait Broadcast[F[_], Request, Response] {
  def broadcast(request: Request): F[Unit]
  def deliver: F[Request]
}

object Broadcast extends BroadcastInstances {
  def apply[F[_], Request, Response](
      implicit B: Broadcast[F, Request, Response]): Broadcast[F, Request, Response] = B
}

sealed abstract class BroadcastInstances {}
