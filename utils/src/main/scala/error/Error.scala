package utils.error

trait Error extends Exception {
  final override def fillInStackTrace(): Throwable = this
}
