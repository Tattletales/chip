package backend

object implicits
    extends gossip.implicits
    with utils.EitherParsing
    with gossip.model.decoderImplicits
    with events.implicits
