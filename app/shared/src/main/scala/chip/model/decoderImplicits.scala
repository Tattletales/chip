package chip.model

import chip.model.Tweet.{Content, ContentTag}
import chip.model.User.{Username, UsernameTag}
import backend.gossip.model.Node.{NodeId, NodeIdTag}
import io.circe.Decoder
import shapeless.tag

trait decoderImplicits {
  implicit def contentDecoder(implicit D: Decoder[String]): Decoder[Content] =
    D.map(tag[ContentTag][String](_))

  implicit def usernameDecoder(implicit D: Decoder[String]): Decoder[Username] =
    D.map(tag[UsernameTag][String](_))
}

object decoderImplicits extends decoderImplicits
