import fs2.Stream

case class Repo[F[_], User, Tweet](users: Users[Stream[F, ?], User],
                                   tweets: Tweets[Stream[F, ?], User, Tweet])
