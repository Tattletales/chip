package backend.gossip

import doobie.util.meta.Meta
import backend.gossip.model.Node.{NodeId, NodeIdTag}
import cats.Functor
import io.circe.{Decoder, Encoder}
import org.http4s.EntityDecoder
import shapeless.tag

trait implicits {
  /* -- NodeId -- */
  implicit def nodeIdMeta(implicit M: Meta[String]): Meta[NodeId] =
    M.xmap(tag[NodeIdTag][String], a => a)

  implicit def nodeIdEncoder(implicit E: Encoder[String]): Encoder[NodeId] = E.contramap(a => a)

  /* nodeIdDecoder is in shared */

  implicit def nodeIdEntityDecoder[F[_]: Functor](
      implicit D: EntityDecoder[F, String]): EntityDecoder[F, NodeId] =
    D.map(tag[NodeIdTag][String])

}
