import simulacrum.typeclass

@typeclass trait Userable[T] {
  def getId(t: T): Int
}