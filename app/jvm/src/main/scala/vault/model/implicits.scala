package vault.model

import cats.Functor
import doobie.util.meta.Meta
import io.circe.{Decoder, Encoder}
import org.http4s.{Entity, EntityDecoder}
import shapeless.tag
import vault.model.Account._

trait implicits {
  implicit def MoneyEncoder(implicit E: Encoder[Double]): Encoder[Money] = E.contramap(a => a)

  implicit def MoneyDecoder(implicit D: Decoder[Double]): Decoder[Money] =
    D.map(tag[MoneyTag][Double])

  implicit def doubleMeta(implicit M: Meta[Double]): Meta[Money] =
    M.xmap(tag[MoneyTag][Double], a => a)
}
