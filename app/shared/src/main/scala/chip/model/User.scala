package chip.model

import chip.model.User.{UserId, Username}
import shapeless.tag.@@

case class User(id: UserId, name: Username)

object User {
  sealed trait UserIdTag
  type UserId = String @@ UserIdTag

  sealed trait UsernameTag
  type Username = String @@ UsernameTag
}
