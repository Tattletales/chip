package vault.model

import gossip.model.Node._
import io.circe.Encoder
import vault.model.Account._

object implicits {
  implicit def nodeIdEncoder(implicit E: Encoder[String]): Encoder[NodeId] = E.contramap(a => a)
  implicit def MoneyEncoder(implicit E: Encoder[Double]): Encoder[Money] = E.contramap(a => a)
}
