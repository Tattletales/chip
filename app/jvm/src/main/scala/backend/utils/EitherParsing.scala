package backend.utils

import io.circe.{Decoder, Encoder}
import io.circe.Decoder.decodeEither
import io.circe.Encoder.encodeEither

trait EitherParsing {
  implicit def eitherDecoder[A: Decoder, B: Decoder]: Decoder[Either[A, B]] =
    decodeEither("l", "r")

  implicit def eitherEncoder[A: Encoder, B: Encoder]: Encoder[Either[A, B]] =
    encodeEither("l", "r")
}
