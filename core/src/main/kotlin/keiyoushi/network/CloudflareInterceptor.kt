package keiyoushi.network

import android.app.Application
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.annotation.MainThread
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.isOutdated
import keiyoushi.utils.ForegroundActivity
import keiyoushi.utils.injektOrAddByKey
import keiyoushi.utils.runWebViewBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.withLock
import kotlin.time.Duration.Companion.milliseconds

internal object CloudflareInterceptor : Interceptor {
    private val listenerScript = """
        addEventListener("message", ({data}) => {
            if (data?.source === "cloudflare-challenge") {
                mihon?.postMessage(data.event);
            }
        })
    """.trimIndent()

    private val networkHelper: NetworkHelper = Injekt.get()

    private typealias LocksData = LinkedHashMap<String, Pair<ReentrantReadWriteLock, ReentrantReadWriteLock>>

    private val locks = object {
        private val data = injektOrAddByKey<LocksData>("CLOUDFLARE_INTERCEPTOR_LOCKS_DATA") {
            object : LocksData() {
                private val MAX_CAPACITY = 256

                override fun removeEldestEntry(
                    eldest: Map.Entry<String, Pair<ReentrantReadWriteLock, ReentrantReadWriteLock>>,
                ): Boolean {
                    if (size > MAX_CAPACITY) {
                        eldest.value.second.writeLock().withLock {
                            if (size > MAX_CAPACITY) {
                                remove(eldest.key)
                            }
                        }
                    }
                    return false
                }
            }
        }

        inline fun <T> withLock(host: String, block: (ReentrantReadWriteLock) -> T): T {
            val (lock, entryLock) = synchronized(data) {
                var entry = data[host]
                if (entry == null) {
                    entry = ReentrantReadWriteLock() to ReentrantReadWriteLock()
                    data[host] = entry
                }
                entry.first to entry.second.readLock().apply { lock() }
            }
            try {
                return block(lock)
            } finally {
                entryLock.unlock()
            }
        }
    }

    private const val DEFAULT_WIDTH = 1920

    private const val DEFAULT_HEIGHT = 1080

    private fun clearance(url: HttpUrl): String? = networkHelper.cookieJar.loadForRequest(url).find { it.name == "cf_clearance" }?.value

    private fun isSolved(url: HttpUrl, oldClearance: String?): Boolean = clearance(url).let { it != oldClearance && !it.isNullOrBlank() }

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()
        val request = chain.request()
        val url = request.url

        locks.withLock(url.host) { lock ->
            val (response, oldClearance) = lock.readLock().withLock {
                if (call.isCanceled()) {
                    throw IOException("Canceled")
                }

                chain.proceed(request).apply {
                    if (header("cf-mitigated") != "challenge") {
                        return this
                    }
                } to clearance(url)
            }

            response.use { response ->
                if (call.isCanceled()) {
                    throw IOException("Canceled")
                }

                lock.writeLock().withLock {
                    if (call.isCanceled()) {
                        throw IOException("Canceled")
                    }

                    if (isSolved(url, oldClearance)) {
                        // Cloudflare solved in another call, skip
                        return@withLock
                    }

                    networkHelper.cookieJar.remove(url, listOf("cf_clearance"), 0)

                    if (!resolveInWebView(chain, response.body, oldClearance)) {
                        networkHelper.cookieJar.remove(url, listOf("cf_clearance"), 0)
                        throw IOException("Failed to bypass Cloudflare")
                    }
                }
            }
        }

        return chain.proceed(request)
    }

    private fun WebView.layOutHeadless() {
        val spec = { n: Int -> View.MeasureSpec.makeMeasureSpec(n, View.MeasureSpec.EXACTLY) }
        measure(spec(DEFAULT_WIDTH), spec(DEFAULT_HEIGHT))
        layout(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        isFocusable = true
        isFocusableInTouchMode = true
        dispatchWindowVisibilityChanged(View.VISIBLE)
        dispatchWindowFocusChanged(true)
        requestFocus()
    }

    private fun WebView.dispatchKeyEvent(action: Int, code: Int): Boolean {
        if (!dispatchKeyEvent(KeyEvent(action, code))) {
            layOutHeadless()
            if (!dispatchKeyEvent(KeyEvent(action, code))) return false
        }
        return true
    }

    @MainThread
    private suspend fun WebView.sendTabSpace(): Boolean {
        if (!dispatchKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)) return false
        delay((10..100).random().milliseconds)
        if (!dispatchKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB)) return false
        delay((10..100).random().milliseconds)
        if (!dispatchKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)) return false
        delay((10..100).random().milliseconds)
        return dispatchKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)
    }

    private fun resolveInWebView(
        chain: Interceptor.Chain,
        body: ResponseBody,
        oldClearance: String?,
    ): Boolean {
        val request = chain.request()
        val url = request.url
        val html = body.string()

        var webViewOutdated = false

        val success = runWebViewBlocking(chain.call()) {
            request.header("User-Agent")?.let { userAgent = it }

            webView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

            if (ForegroundActivity.viewGroup == null) {
                webView.layOutHeadless()
            } else {
                webView.apply {
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
            }

            var fail = false
            var complete = false

            fun handleEvent(event: String) {
                when (event) {
                    "interactiveBegin" -> {
                        // Get the current view group
                        val container = ForegroundActivity.viewGroup
                        if (container == null) {
                            webView.layOutHeadless()
                        }

                        CoroutineScope(Dispatchers.Main).launch {
                            if (container != null) {
                                val width = container.width.takeIf { it > 0 } ?: DEFAULT_WIDTH
                                val height = container.height.takeIf { it > 0 } ?: DEFAULT_HEIGHT

                                // Set translationX to negative width.
                                // The WebView should be offscreen even when the orientation changes.
                                webView.translationX = -width.toFloat()

                                // Attach the WebView to the view group so we can send key events.
                                container.addView(webView, ViewGroup.LayoutParams(width, height))
                            }

                            // Send Tab and Space to check the checkbox, and fall back to JavaScript solver
                            // if dispatchKeyEvent fails.
                            if (!webView.sendTabSpace()) {
                                fail = true
                                webViewOutdated = webView.isOutdated()
                                resolve(false)
                                return@launch
                            }

                            // Challenge should complete in a short amount of time
                            delay(5000.milliseconds)
                            if (!complete) {
                                fail = true
                                webViewOutdated = webView.isOutdated()
                                resolve(false)
                            }
                        }
                    }
                    "complete" -> {
                        complete = true
                    }
                    "fail" -> {
                        // Challenge failed, abort
                        fail = true
                        webViewOutdated = webView.isOutdated()
                        resolve(false)
                    }
                }
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
                // Use an isolated world so the page cannot see our bridge
                val world = WebViewCompat.getExecutionWorld(webView, "mihon")
                val allowedOriginRules = mutableSetOf("${url.scheme}://${url.host}")

                WebViewCompat.addWebMessageListener(webView, "mihon", allowedOriginRules, world) {
                        _,
                        message,
                        _,
                        isMainFrame,
                        _,
                    ->
                    if (isMainFrame) {
                        message.data?.let { handleEvent(it) }
                    }
                }

                // Listen for message events
                WebViewCompat.addJavaScriptOnEvent(
                    webView,
                    listenerScript,
                    WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                    allowedOriginRules,
                    world,
                )
            } else {
                webView.addJavascriptInterface(
                    object {
                        @Suppress("unused")
                        @JavascriptInterface
                        fun postMessage(event: String) = handleEvent(event)
                    },
                    "mihon",
                )
            }

            onPageFinished {
                if (!fail && isSolved(url, oldClearance)) {
                    resolve(true)
                    return@onPageFinished
                }

                if (it == url.toString() &&
                    !WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)
                ) {
                    // Listen for message events
                    evaluateJs(listenerScript)
                }
            }

            loadData(url.toString(), html)
        }

        if (webViewOutdated) {
            Toast.makeText(
                Injekt.get<Application>(),
                "Please update the WebView app for better compatibility",
                Toast.LENGTH_LONG,
            ).show()
        }

        return success
    }
}
