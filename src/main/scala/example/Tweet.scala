package example

import cats.data.State
import cats.{Applicative, Functor, Id}
import cats.implicits._
import example.Interpreters.{stateKV, tweets}

trait KVStore[F[_], K, V] {
  def put(k: K, v: V): F[Unit]
  def get(k: K): F[Option[V]]
}

trait Tweets[F[_], User, Tweet] {
  def postTweet(user: User, tweet: Tweet): F[Unit]
  def getTweets(user: User): F[List[Tweet]]
}

object Interpreters {
  def kv[K, V]: KVStore[Id, K, V] = new KVStore[Id, K, V] {
    def put(k: K, v: V): Id[Unit] = implicitly[Applicative[Id]].unit
    def get(k: K): Id[Option[V]] =
      implicitly[Applicative[Id]].pure(None)
  }

  def stateKV[K, V]: KVStore[State[Map[K, V], ?], K, V] =
    new KVStore[State[Map[K, V], ?], K, V] {
      def put(k: K, v: V): State[Map[K, V], Unit] =
        State.modify(_ + (k -> v))
      def get(k: K): State[Map[K, V], Option[V]] =
        State.inspect(_.get(k))
    }

  def tweets[F[_]: Functor, User, Tweet](
      kvStore: KVStore[F, User, List[Tweet]]) =
    new Tweets[F, User, Tweet] {
      override def postTweet(user: User, tweet: Tweet): F[Unit] =
        kvStore.put(user, List(tweet))
      override def getTweets(user: User): F[List[Tweet]] =
        kvStore.get(user).map(_.getOrElse(List.empty))
    }
}

object Bla extends App {
  type Tweet = String
  type User = Int

  val a = stateKV[User, List[Tweet]] // KVSTore[Task, User, List[Tweet]]
  val b = tweets(a)
  
  val p = for {
    _ <- b.postTweet(2, "World")
    t1 <- b.getTweets(1)
    t2 <- b.getTweets(2)
  } yield t1 ++ t2

  print(p.run(Map(1 -> List("Hello "))).map(_._2).value)
}
