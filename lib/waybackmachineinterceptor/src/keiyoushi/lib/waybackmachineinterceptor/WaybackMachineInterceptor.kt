package keiyoushi.lib.waybackmachineinterceptor

import android.content.SharedPreferences
import android.util.Log
import keiyoushi.lib.waybackmachineinterceptor.RateLimit.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import okio.EOFException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Semaphore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

class WaybackMachineInterceptor(
    private val include: Regex = ".*".toRegex(),
    snapshotMaxAge: Duration = 1.days,
    private val preferences: SharedPreferences? = null,
) : Interceptor {
    private val snapshotMaxAgeMS = snapshotMaxAge.inWholeMilliseconds
    private val snapshotSemaphore = Semaphore(6)
    private val spnSnapshotSemaphore = Semaphore(12)

    // LinkedHashMap with a capacity of URL_CACHE_MAX_ENTRIES. When exceeding the capacity the oldest entry is removed.
    private val urlCache = object : LinkedHashMap<HttpUrl, HttpUrl>() {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<HttpUrl, HttpUrl>?,
        ): Boolean = size > URL_CACHE_MAX_ENTRIES
    }

    /**
     * Get a timestamp from a Wayback Machine URL without a timestamp
     */
    private fun getTimestamp(
        chain: Interceptor.Chain,
        archiveUrl: HttpUrl,
    ): String? = snapshotSemaphore.acquire().let {
        try {
            chain.proceed(
                chain
                    .request()
                    .newBuilder()
                    .url(archiveUrl)
                    .build(),
            ).use { response ->
                response.header("Location")?.let {
                    TIMESTAMP_REGEX.find(it)?.value
                }
            }
        } finally {
            snapshotSemaphore.release()
        }
    }

    /**
     * Get the "id_" url, which points to the raw, unmodified content
     */
    private fun getSnapshotUrl(
        timestamp: String,
        url: HttpUrl,
    ): HttpUrl = "$WEB_PREFIX${timestamp}id_/$url".toHttpUrl()

    /**
     * Create a new URL to retry the snapshot
     */
    private fun getRetryUrl(url: HttpUrl): HttpUrl = url
        .newBuilder()
        .setQueryParameter(RANDOM_QUERY_PARAM, UUID.randomUUID().toString())
        .build()

    private fun timestampIsExpired(timestamp: String): Boolean = System.currentTimeMillis() - DATE_FORMAT.parse(
        timestamp,
    )!!.time > snapshotMaxAgeMS

    private fun spn(
        chain: Interceptor.Chain,
        credentials: String,
        body: FormBody,
    ): Response = chain.proceed(
        chain
            .request()
            .newBuilder()
            .url(SAVE_PREFIX)
            .header("Accept", "application/json")
            .header("Authorization", "LOW $credentials")
            .post(body)
            .build(),
    )

    @Serializable
    private class SaveResponse(
        val job_id: String,
    )

    @Serializable
    private class StatusResponse(
        val status: String,
        val status_ext: String?,
        val timestamp: String?,
    )

    /**
     * Gets the response from the Wayback Machine without following redirects
     */
    private fun getImmediateResponse(
        chain: Interceptor.Chain,
        url: HttpUrl,
    ): Response = chain.proceed(
        chain.request().newBuilder().url(
            urlCache[url]?.let { cachedUrl ->
                if (TIMESTAMP_REGEX.find(cachedUrl.toString())?.value?.let {
                        timestampIsExpired(it)
                    } ?: false
                ) {
                    // URL expired
                    urlCache.remove(url)
                    null
                } else {
                    cachedUrl
                }
            } ?: if (url.host == HOST || !include.matches(url.toString())) {
                // url is a Wayback Machine URL or isn't matched, do nothing
                url
            } else {
                val credentials = preferences?.getWaybackMachineS3CredentialsPref()
                if (credentials?.isNotEmpty() == true) {
                    spnSnapshotSemaphore.acquire()

                    try {
                        val id = spn(
                            chain,
                            credentials,
                            FormBody
                                .Builder()
                                .add("url", url.toString())
                                .add("if_not_archived_within", (snapshotMaxAgeMS / 1000).toString())
                                .add("skip_first_archive", "1")
                                .add("force_get", "1")
                                .build(),
                        ).body.string().parseAs<SaveResponse>().job_id

                        var statusResponse = spn(
                            chain,
                            credentials,
                            FormBody
                                .Builder()
                                .add("job_id", id)
                                .build(),
                        ).body.string().parseAs<StatusResponse>()

                        while (statusResponse.status == "pending") {
                            statusResponse = spn(
                                chain,
                                credentials,
                                FormBody
                                    .Builder()
                                    .add("job_id", id)
                                    .build(),
                            ).body.string().parseAs<StatusResponse>()

                            Thread.sleep(5000)
                        }

                        if (statusResponse.status != "success") {
                            throw Exception("Failed to archive page: ${statusResponse.status_ext}")
                        } else {
                            getSnapshotUrl(statusResponse.timestamp!!, url)
                        }
                    } finally {
                        spnSnapshotSemaphore.release()
                    }
                } else {
                    getTimestamp(chain, "$WEB_PREFIX$url".toHttpUrl())?.let { timestamp ->
                        if (timestampIsExpired(timestamp)) {
                            // snapshot is expired, attempt to create a new snapshot
                            snapshot(chain, url) ?: getSnapshotUrl(timestamp, url)
                        } else {
                            // snapshot is recent
                            getSnapshotUrl(timestamp, url)
                        }
                    }

                        // snapshot doesn't exist, create a new snapshot
                        ?: snapshot(chain, url)

                        // archiving failed
                        ?: throw Exception("Failed to archive page")
                }
            },
        ).build(),
    )

    /**
     * Resolves all redirects
     */
    private fun resolveRedirects(
        chain: Interceptor.Chain,
        url: HttpUrl,
    ): Response {
        var response = getImmediateResponse(chain, url)

        // resolve all redirects
        while (response.isRedirect) {
            val newUrl = response.header("Location")?.toHttpUrl() ?: break
            response.close()
            response = getImmediateResponse(chain, newUrl)
        }

        return response
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url
        var response = resolveRedirects(chain, url)

        // Cache the url
        urlCache[url] = response.request.url

        if (response.request.url.host == HOST) {
            // Sometimes, the response is truncated. This prevents an EOFException
            response = response.use { response ->
                response.newBuilder().headers(
                    response.headers.newBuilder()
                        .removeAll("Content-Encoding")
                        .removeAll("Content-Length")
                        .build(),
                ).body(
                    Buffer().also {
                        val stream = response.body.byteStream()
                        val out = it.outputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            try {
                                val len = stream.read(buf)
                                if (len <= 0) break
                                out.write(buf, 0, len)
                            } catch (_: EOFException) {
                                Log.e("WaybackMachine", "Response truncated")
                                break
                            }
                        }
                    }.asResponseBody(response.body.contentType()),
                ).build()
            }
        }

        return response
    }

    private inline fun <R> snapshotHelper(
        chain: Interceptor.Chain,
        url: HttpUrl,
        block: (String, HttpUrl) -> R?,
    ): R? = rateLimit {
        getTimestamp(chain, "$SAVE_PREFIX$url".toHttpUrl())
    }?.let { timestamp ->
        block(timestamp, url)
    } ?: getRetryUrl(url).let { retryUrl ->
        // Retry snapshot with a new URL
        rateLimit {
            getTimestamp(chain, "$SAVE_PREFIX$retryUrl".toHttpUrl())
        }?.let { timestamp ->
            block(timestamp, retryUrl)
        }
    }

    /**
     * Creates a snapshot and returns the snapshot URL
     */
    private fun snapshot(
        chain: Interceptor.Chain,
        url: HttpUrl,
    ): HttpUrl? = snapshotHelper(chain, url, ::getSnapshotUrl)

    companion object {
        private const val HOST = "web.archive.org"
        private const val SAVE_PREFIX = "https://$HOST/save/"
        private const val WEB_PREFIX = "https://$HOST/web/"
        private const val RANDOM_QUERY_PARAM = "__WaybackMachineInterceptor_RANDOM_QUERY_PARAM__"
        private const val URL_CACHE_MAX_ENTRIES = 250
        private val TIMESTAMP_REGEX = """(?<=://${Regex.escape(HOST)}/web/)\d{14}""".toRegex()
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }
}
