package vault.model

import backend.gossip.model.Node._
import io.circe.Encoder
import vault.model.Account._

trait implicits {
  implicit def MoneyEncoder(implicit E: Encoder[Double]): Encoder[Money] = E.contramap(a => a)
}
