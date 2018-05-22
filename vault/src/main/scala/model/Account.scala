package vault.model

import backend.gossip.Node.NodeId
import shapeless.tag.@@
import vault.model.Account.{Money, User}

case class Account(owner: User, balance: Money)

object Account {
  type User = NodeId

  sealed trait MoneyTag
  type Money = Double @@ MoneyTag
}
