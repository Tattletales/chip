package chip
package model

import User.{UserId, Username}
import backend.gossip.Node.NodeId
import shapeless.tag.@@

final case class User(id: UserId, name: Username)

object User {
  type UserId = NodeId

  sealed trait UsernameTag
  type Username = String @@ UsernameTag
}
