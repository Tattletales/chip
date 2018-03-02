import simulacrum.typeclass

@typeclass trait Tweetable[T] {
  def getText(t: T): String
}