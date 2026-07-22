package keiyoushi.lib.waybackmachineinterceptor

import android.os.SystemClock
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

internal object RateLimit {
    const val PERMITS = 15
    val rateLimit = 1.minutes
    val queue = ArrayDeque<Duration>(PERMITS)
    val lock = ReentrantLock(true)
    val condition: Condition = lock.newCondition()

    fun rateLimit(): Unit = lock.withLock {
        while (queue.size >= PERMITS) { // queue is full, remove expired entries
            val periodStart = SystemClock.elapsedRealtime().milliseconds - rateLimit
            if (!queue.isEmpty() && queue.first() <= periodStart) {
                queue.removeFirst()
                while (!queue.isEmpty() && queue.first() <= periodStart) {
                    queue.removeFirst()
                }
                break
            }
            try {
                condition.awaitNanos((queue.first() - periodStart).inWholeNanoseconds)
            } catch (_: InterruptedException) {
                continue
            }
        }

        queue.addLast(SystemClock.elapsedRealtime().milliseconds)
    }
}
