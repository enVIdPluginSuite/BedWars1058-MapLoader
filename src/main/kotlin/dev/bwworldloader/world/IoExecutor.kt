package dev.bwworldloader.world

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Small dedicated thread pool for copying / zipping / deleting maps.
 *
 * We deliberately do not use the Bukkit async scheduler: it is unbounded, so loading hundreds of maps could
 * saturate the disk, and we want a hard cap on parallel file operations ([resize] makes it configurable).
 */
class IoExecutor(private val log: Logger, threads: Int) {

    @Volatile
    private var pool: ThreadPoolExecutor = create(threads)

    @Volatile
    private var threadCount = threads

    private fun create(threads: Int): ThreadPoolExecutor {
        val counter = AtomicInteger()
        val factory = ThreadFactory { r ->
            Thread(r, "BWWorldLoader-IO-${counter.incrementAndGet()}").apply { isDaemon = true }
        }
        return ThreadPoolExecutor(threads, threads, 30L, TimeUnit.SECONDS, LinkedBlockingQueue(), factory)
            .apply { allowCoreThreadTimeOut(true) }
    }

    /** Applies a new thread count; running tasks finish on the old pool. */
    @Synchronized
    fun resize(threads: Int) {
        if (threads == threadCount) return
        val old = pool
        pool = create(threads)
        threadCount = threads
        old.shutdown()
    }

    /** Runs [task] on the IO pool. Exceptions are logged and never escape. */
    fun submit(task: () -> Unit) {
        val wrapped = Runnable {
            try {
                task()
            } catch (t: Throwable) {
                log.log(Level.SEVERE, "Background world task failed", t)
            }
        }
        try {
            pool.execute(wrapped)
        } catch (ex: RejectedExecutionException) {
            // only happens while shutting down: just do the work on the calling thread
            wrapped.run()
        }
    }

    /** Waits (bounded) for queued work such as pending map deletions to finish. */
    fun shutdown(timeoutSeconds: Long) {
        val p = pool
        p.shutdown()
        try {
            if (!p.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                log.warning("Background world tasks are still running; some runtime worlds may be cleaned up on the next start.")
                p.shutdownNow()
            }
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
