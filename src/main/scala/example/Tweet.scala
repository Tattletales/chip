package example

import cats.data.{OptionT, State}
import cats.{Applicative, Monad}
import cats.implicits._

trait TweetsAlg[F[_], User, Tweet] {
  def putTweet(user: User, tweet: Tweet): F[Unit]
}

class TweetsViaKVStore[F[_]: Monad, User, Tweet](db: KVStoreAlg[F, User, List[Tweet]])
    extends TweetsAlg[F, User, Tweet] {
  override def putTweet(user: User, tweet: Tweet): F[Unit] = db.get(user).flatMap { tweets =>
    db.put(user, tweet :: tweets.getOrElse(List.empty))
  }
}

trait UsersAlg[F[_], User, Tweet] {
  def login(username: String, password: String): F[Option[User]]
  def tweet(tweet: Tweet): F[Option[Unit]]
}

class UsersInterpreter[F[_]: Applicative, User, Tweet](tweets: TweetsAlg[F, User, Tweet])(
    implicit U: Userable[User])
    extends UsersAlg[F, User, Tweet] {
  def login(username: String, password: String): F[Option[User]] =
    implicitly[Applicative[F]].pure(Some(U.default))

  def tweet(tweet: Tweet): F[Option[Unit]] =
    (for {
      user <- OptionT(login("bla", "bla"))
      _ = tweets.putTweet(user, tweet)
    } yield ()).value
}

trait Userable[T] {
  def default: T
}

object Userable {
  implicit object UserableInt extends Userable[Int] {
    override def default: Int = 0
  }
}

trait KVStoreAlg[F[_], K, V] {
  def put(k: K, v: V): F[Unit]
  def get(k: K): F[Option[V]]
}

object Interpreters {
  def stateKV[K, V]: KVStoreAlg[State[Map[K, V], ?], K, V] = new KVStoreAlg[State[Map[K, V], ?], K, V] {
    def put(k: K, v: V): State[Map[K, V], Unit] = State.modify(_ + (k -> v))
    def get(k: K): State[Map[K, V], Option[V]] = State.inspect(_.get(k))
  }
}

class StateKV[K, V] extends KVStoreAlg[State[Map[K, V], ?], K, V] {
  override def put(k: K, v: V): State[Map[K, V], Unit] = State.modify(_ + (k -> v))

  override def get(k: K): State[Map[K, V], Option[V]] = State.inspect(_.get(k))
}

object F {
  type User = Int
  type Tweet = String
  
  val db = Interpreters.stateKV[User, List[Tweet]]
  val tweetsDB = new TweetsViaKVStore[State[Map[User, List[Tweet]], ?], User, Tweet](db)
  val users = new UsersInterpreter[State[Map[User, List[Tweet]], ?], User, Tweet](tweetsDB)
  
  users.tweet("Hello")
}
