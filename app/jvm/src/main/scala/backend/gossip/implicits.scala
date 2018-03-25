package backend.gossip

import doobie.util.meta.Meta
import backend.gossip.model.Node.{NodeId, NodeIdTag}
import io.circe.Encoder
import shapeless.tag

trait implicits {
  /* -- NodeId -- */
  implicit def nodeIdMeta(implicit M: Meta[String]): Meta[NodeId] =
    M.xmap(tag[NodeIdTag][String], a => a)

  implicit def nodeIdEncoder(implicit E: Encoder[String]): Encoder[NodeId] = E.contramap(a => a)
}
