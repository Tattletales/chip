package vault
package errors

import model.{Money, User}
import backend.events._
import utils.error.Error
import events.Withdraw

/**
  * Accounts related errors
  */
sealed trait AccountsError extends Error
final case class AccountNotFound(user: User) extends AccountsError {
  override def toString: String = s"No account for $user."
}
final case class InsufficentFunds(currentAmount: Money, of: User) extends AccountsError
case object UnknownUser extends AccountsError
case object TransferError extends AccountsError

/**
  * Transaction related errors
  */
sealed trait TransactionStageError extends Error

final case class PayloadDecodingError(payload: Payload) extends TransactionStageError {
  override def toString: String = s"Failed decoding the payload $payload.\n"
}

final case class SenderError(m: String) extends TransactionStageError

final case class MissingLsnError(w: Withdraw) extends TransactionStageError {
  override def toString: String = s"Missing lsn in $w. It should have been added in the decoder."
}
