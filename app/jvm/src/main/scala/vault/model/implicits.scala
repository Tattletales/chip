package vault.model

import io.circe.{Decoder, Encoder}
import shapeless.tag
import vault.model.Account._

trait implicits {
  implicit def MoneyEncoder(implicit E: Encoder[Double]): Encoder[Money] = E.contramap(a => a)

  implicit def MoneyDecoder(implicit D: Decoder[Double]): Decoder[Money] =
    D.map(tag[MoneyTag][Double])
}
