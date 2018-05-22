package chip.model

import chip.model.User.{UserId, Username}
import backend.gossip.Node.NodeId
import shapeless.tag.@@

case class User(id: UserId, name: Username)

object User {
  type UserId = NodeId

  sealed trait UsernameTag
  type Username = String @@ UsernameTag
}
