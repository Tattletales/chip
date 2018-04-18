package vault.model

import backend.events.Subscriber.Payload
import vault.model.Account.User

sealed trait VaultError extends Throwable

case class PayloadDecodingError(payload: Payload, m: String) extends VaultError {
  override def toString: String = s"Failed decoding the payload $payload.\n$m"
}

case class SenderError(m: String) extends VaultError

case class AccountNotFoundError(user: User) extends VaultError {
  override def toString: String = s"No account for $user."
}
