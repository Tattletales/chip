package vault.model

import backend.gossip.Node.NodeId
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive
import vault.model.Account.{Money, User}

case class Account(owner: User, balance: Money)

object Account {
  type User = NodeId

  type Money = Double Refined Positive
}
