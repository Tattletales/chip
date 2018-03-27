package backend.utils

import io.circe.{Decoder, Encoder}

trait EitherParsing {
  implicit def eitherDecoder[A, B](implicit A: Decoder[A], B: Decoder[B]): Decoder[Either[A, B]] = {
    val a: Decoder[Either[A, B]] = A.map(Left.apply)
    val b: Decoder[Either[A, B]] = B.map(Right.apply)

    a.or(b)
  }
}
