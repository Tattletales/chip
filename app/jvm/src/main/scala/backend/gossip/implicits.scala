package backend.gossip

import backend.events.Subscriber.{EventId, EventIdTag}
import doobie.util.meta.Meta
import backend.gossip.model.Node.{NodeId, NodeIdTag}
import io.circe.{Decoder, Encoder}
import shapeless.tag

trait implicits {
  /* -- NodeId -- */
  implicit def nodeIdMeta(implicit M: Meta[String]): Meta[NodeId] =
    M.xmap(tag[NodeIdTag][String], a => a)

  implicit def nodeIdEncoder(implicit E: Encoder[String]): Encoder[NodeId] = E.contramap(a => a)

  /* EventId */
  implicit def eventIdEncoder(implicit E: Encoder[Int]): Encoder[EventId] = E.contramap(a => a)

  implicit def eventIdDecoder(implicit D: Decoder[Int]): Decoder[EventId] =
    D.map(tag[EventIdTag][Int])
}
