package keiyoushi.lib.waybackmachineinterceptor

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.POST
import keiyoushi.lib.waybackmachineinterceptor.RateLimit.rateLimit
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Semaphore
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

internal object NetworkInterceptor : Interceptor {
    private val captureSemaphore by lazy { Semaphore(6) }
    private val spnCaptureSemaphore by lazy { Semaphore(12) }
    private val client by lazy {
        Injekt
            .get<NetworkHelper>()
            .client
            .newBuilder()
            .readTimeout(60.seconds)
            .followRedirects(false)
            .build()
    }

    private inline fun <T> Semaphore.withPermit(action: () -> T): T = run {
        acquire()
        try {
            action()
        } finally {
            release()
        }
    }

    /**
     * Get a timestamp from a Wayback Machine URL without a timestamp
     */
    private fun getTimestamp(archiveUrl: HttpUrl): String? = client
        .newCall(GET(archiveUrl, Headers.Builder().build()))
        .execute()
        .use { response ->
            response.header("Location")?.let {
                TIMESTAMP_REGEX.find(it)?.value
            }
        }

    /**
     * Get the "id_" url, which points to the raw, unmodified content
     */
    private fun getCaptureUrl(
        timestamp: String,
        url: HttpUrl,
    ): HttpUrl = "$WEB_PREFIX${timestamp}id_/$url".toHttpUrl()

    /**
     * Create a new URL to retry the capture
     */
    private fun getRetryUrl(url: HttpUrl): HttpUrl = url
        .newBuilder()
        .setQueryParameter(RANDOM_QUERY_PARAM, UUID.randomUUID().toString())
        .build()

    private fun timestampIsExpired(
        timestamp: String,
        captureMaxAge: Duration,
    ): Boolean = System.currentTimeMillis() - DATE_FORMAT.parse(
        timestamp,
    )!!.time > captureMaxAge.inWholeMilliseconds

    private fun spn(
        credentials: String,
        body: FormBody,
    ): Response = client.newCall(
        POST(
            SAVE_PREFIX,
            Headers
                .Builder()
                .add("Accept", "application/json")
                .add("Authorization", "LOW $credentials")
                .build(),
            body,
        ),
    ).execute()

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

    override fun intercept(chain: Interceptor.Chain): Response {
        val call = chain.call()

        if (call.isCanceled()) throw IOException("Canceled")

        val request = chain.request()
        val origUrl = request.url
        val origUrlStr = origUrl.toString()

        if (!origUrlStr.startsWith("${WEB_PREFIX}http")) {
            return chain.proceed(request)
        }

        val url = origUrlStr.substring(WEB_PREFIX.length).toHttpUrl()
        val preferences = request.tag(SharedPreferences::class.java)
        val captureMaxAge = preferences?.getWaybackMachineCaptureMaxAgePref()?.let {
            Duration.parse(it)
        } ?: 1.days
        val credentials = preferences?.getWaybackMachineS3CredentialsPref()

        return chain.proceed(
            request.newBuilder().url(
                getTimestamp(origUrl).let { timestamp ->
                    if (timestamp != null && !timestampIsExpired(timestamp, captureMaxAge)) {
                        getCaptureUrl(timestamp, url)
                    } else {
                        capture(
                            url,
                            request.headers,
                            captureMaxAge,
                            credentials,
                        ) ?: getCaptureUrl(
                            timestamp ?: throw IOException("Failed to archive page $url"),
                            url,
                        )
                    }
                },
            ).build(),
        )
    }

    private fun captureHelper(
        url: HttpUrl,
        headers: Headers,
        captureMaxAge: Duration,
        credentials: String?,
    ): String? = rateLimit().run {
        if (credentials?.isNotEmpty() == true) {
            spnCaptureSemaphore.withPermit {
                val id = spn(
                    credentials,
                    FormBody
                        .Builder()
                        .add("url", url.toString())
                        .add("force_get", "1")
                        .add("skip_first_archive", "1")
                        .add("if_not_archived_within", captureMaxAge.inWholeSeconds.toString())
                        .add("capture_cookie", headers["Cookie"] ?: "")
                        .add("use_user_agent", headers["User-Agent"] ?: "")
                        .build(),
                ).body.string().parseAs<SaveResponse>().job_id

                var statusResponse = spn(
                    credentials,
                    FormBody
                        .Builder()
                        .add("job_id", id)
                        .build(),
                ).body.string().parseAs<StatusResponse>()

                while (statusResponse.status == "pending") {
                    statusResponse = spn(
                        credentials,
                        FormBody
                            .Builder()
                            .add("job_id", id)
                            .build(),
                    ).body.string().parseAs<StatusResponse>()

                    Thread.sleep(5000)
                }

                if (statusResponse.status_ext != null) {
                    Toast.makeText(
                        Injekt.get<Application>(),
                        "Failed to archive page $url: ${statusResponse.status_ext}",
                        Toast.LENGTH_LONG,
                    ).show()
                }

                statusResponse.timestamp
            }
        } else {
            captureSemaphore.withPermit {
                getTimestamp("$SAVE_PREFIX$url".toHttpUrl()).also {
                    if (it == null) {
                        Toast.makeText(
                            Injekt.get<Application>(),
                            "Failed to archive page $url",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private inline fun <R> capture(
        url: HttpUrl,
        headers: Headers,
        captureMaxAge: Duration,
        credentials: String?,
        block: (String, HttpUrl) -> R?,
    ): R? = captureHelper(url, headers, captureMaxAge, credentials)?.let { timestamp ->
        block(timestamp, url)
    } ?: getRetryUrl(url).let { retryUrl ->
        // Retry capture with a new URL
        captureHelper(retryUrl, headers, captureMaxAge, credentials)?.let { timestamp ->
            block(timestamp, retryUrl)
        }
    }

    /**
     * Creates a capture and returns the capture URL
     */
    private fun capture(
        url: HttpUrl,
        headers: Headers,
        captureMaxAge: Duration,
        credentials: String?,
    ): HttpUrl? = capture(
        url,
        headers,
        captureMaxAge,
        credentials,
        ::getCaptureUrl,
    )
}
