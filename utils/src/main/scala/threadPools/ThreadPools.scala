package threadPools

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.{Executors, ThreadFactory}

import scala.concurrent.ExecutionContext

object ThreadPools {

  /**
    * Thread pool for non-blocking.
    *
    * It has a bound on the number of threads so things to block should be run on the `BlockingThreadPool`.
    */
  val MainThreadPool: ExecutionContext = ExecutionContext.global

  /**
    * Thread pool for blocking IO.
    *
    * Source: https://github.com/alexandru/scala-best-practices/blob/master/sections/4-concurrency-parallelism.md
    */
  val BlockingIOThreadPool: ExecutionContext =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool(new ThreadFactory {
      private val counter = new AtomicLong(0L)

      def newThread(r: Runnable) = {
        val th = new Thread(r)
        th.setName(
          "io-thread-" +
            counter.getAndIncrement.toString)
        th.setDaemon(true)
        th
      }
    }))
}
