package vault.model

import backend.events._
import shapeless.tag
import vault.events.TransactionStage

trait implicits {
  implicit val eventTyper: EventTyper[TransactionStage] = new EventTyper[TransactionStage] {
    def eventType: EventType = tag[EventTypeTag][String]("AccountsEvent")
  }
}
