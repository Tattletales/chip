import fs2.Stream

case class Repo[F[_]](users: Users[Stream[F, ?]],
                                   tweets: Tweets[Stream[F, ?]])
