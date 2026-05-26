package keiyoushi.lib.waybackmachineinterceptor

import android.os.SystemClock
import java.util.ArrayDeque
import kotlin.time.Duration.Companion.minutes

internal object RateLimit {
    const val PERMITS = 15
    val requestQueue = ArrayDeque<Long>(PERMITS)
    val rateLimitMillis = 1.minutes.inWholeMilliseconds

    inline fun <R> rateLimit(block: () -> R): R {
        val timestamp: Long

        synchronized(requestQueue) {
            while (requestQueue.size >= PERMITS) { // queue is full, remove expired entries
                val periodStart = SystemClock.elapsedRealtime() - rateLimitMillis
                var hasRemovedExpired = false
                while (!requestQueue.isEmpty() && requestQueue.first() <= periodStart) {
                    requestQueue.removeFirst()
                    hasRemovedExpired = true
                }
                if (hasRemovedExpired) {
                    break
                }
                try { // wait for the first entry to expire, or notified by cached response
                    (requestQueue as Object).wait(requestQueue.first() - periodStart)
                } catch (_: InterruptedException) {
                    continue
                }
            }

            // add request to queue
            timestamp = SystemClock.elapsedRealtime()
            requestQueue.addLast(timestamp)
        }

        return block()
    }
}
