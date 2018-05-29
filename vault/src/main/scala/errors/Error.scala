package vault.errors

import backend.events.Event.Payload
import vault.model.Account.{Money, User}
import utils.error.Error
import vault.events.Withdraw

/**
  * Accounts related errors
  */
sealed trait AccountsError extends Error
case class AccountNotFound(user: User) extends AccountsError {
  override def toString: String = s"No account for $user."
}
case class InsufficentFunds(currentAmount: Money, of: User) extends AccountsError
case object UnknownUser extends AccountsError
case object TransferError extends AccountsError

/**
  * Transaction related errors
  */
sealed trait TransactionStageError extends Error

case class PayloadDecodingError(payload: Payload) extends TransactionStageError {
  override def toString: String = s"Failed decoding the payload $payload.\n"
}

case class SenderError(m: String) extends TransactionStageError

case class MissingLsnError(w: Withdraw) extends TransactionStageError {
  override def toString: String = s"Missing lsn in $w. It should have been added in the decoder."
}
