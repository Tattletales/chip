package backend.events

import cats.Monad
import fs2.{Pipe, Pull, Stream}
import backend.events.CausalEvent.ops._

object EventOrder {

  /**
    * Re-orders the events `E` in causal order.
    * Warning: an event `E` can cause AT-MOST ONE event `E`.
    */
  def causalOrder[F[_]: Monad, E: CausalEvent]: Pipe[F, E, E] = {
    def go(s: Stream[F, E], delivered: Set[Lsn], waitingFor: Map[Lsn, E]): Pull[F, E, Unit] =
      Stream
        .InvariantOps(s)
        .pull
        .uncons1
        .flatMap[F, E, Unit] {
          case Some((e, es)) =>
            (e.causedBy, waitingFor.get(e.lsn)) match {
              // Deliver `e` but first the events waiting for it.
              case (Some(causedBy), Some(releasable)) if delivered(causedBy) =>
                Pull.output1(releasable) >> Pull.output1(e) >> go(
                  es,
                  delivered - causedBy, // Not needed anymore as there's only one event depending on `causedBy`
                  waitingFor - e.lsn)

              // Deliver `e`
              case (Some(causedBy), None) if delivered(causedBy) =>
                Pull.output1(e) >> go(es, delivered - causedBy, waitingFor)

              // Cannot deliver `e` as the message it was cause by hasn't been delivered.
              case (Some(causedBy), _) => go(es, delivered, waitingFor + (causedBy -> e))

              // Deliver `e` (it is independent) but first deliver the events waiting for it.
              case (None, Some(releasable)) =>
                Pull.output1(releasable) >> Pull.output1(e) >> go(es,
                                                                  delivered - e.lsn,
                                                                  waitingFor - e.lsn)

              // Deliver `e` (it is independent)
              case (None, None) =>
                Pull.output1(e) >> go(es,
                                      // There's no need to store `e` in delivered if not event can depend on it.
                                      if (e.canCause) { delivered + e.lsn } else delivered,
                                      waitingFor)
            }
          case None => Pull.done
        }

    go(_, Set.empty, Map.empty).stream
  }
}
