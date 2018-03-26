package backend.gossip.model

import backend.gossip.model.Node.{NodeId, NodeIdTag}
import io.circe.Decoder
import shapeless.tag

trait decoderImplicits {
  implicit def nodeIdDecoder(implicit D: Decoder[String]): Decoder[NodeId] =
    D.map(tag[NodeIdTag][String])
}

object decoderImplicits extends decoderImplicits
