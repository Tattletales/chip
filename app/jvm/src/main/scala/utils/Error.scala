package utils

/**
  * Supertype of all errors
  */
trait Error extends Exception {
  final override def fillInStackTrace(): Throwable = this
}
