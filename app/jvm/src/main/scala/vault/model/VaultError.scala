package vault.model

import backend.events.Subscriber.Payload
import vault.model.Account.User

sealed trait VaultError extends Throwable
