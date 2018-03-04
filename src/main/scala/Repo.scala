case class Repo[F[_], User, Tweet](
    users: Users[F, User],
    tweets: Tweets[F, User, Tweet])