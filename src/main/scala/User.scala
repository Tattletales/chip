import User._

case class User(id: UserId, name: Name, password: Password)

object User {
  case class UserId(id: Int) extends AnyVal
  case class Name(name: String) extends AnyVal
  case class Password(p: String) extends AnyVal
}
