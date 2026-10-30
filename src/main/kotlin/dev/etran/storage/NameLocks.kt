package dev.etran.storage

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/**
 * Per-name locks so that, for one map, copying it out of the library, saving it back after `/bw setupArena` and
 * deleting a runtime copy can never run at the same time on the different IO threads.
 *
 * Lock order is always `tpl:<name>` (the library entry) before `rt:<name>` (the world folder in the server root).
 */
object NameLocks {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun tpl(name: String) = "tpl:" + name.lowercase(Locale.ROOT)
    fun rt(name: String) = "rt:" + name.lowercase(Locale.ROOT)

    fun <T> with(key: String, block: () -> T): T {
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
