package utils
package error

trait Error extends Exception {
  final override def fillInStackTrace(): Throwable = this
}
