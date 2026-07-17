package keiyoushi.lib.waybackmachineinterceptor

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import keiyoushi.lib.waybackmachineinterceptor.RateLimit.rateLimit
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.parseAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Semaphore
import kotlin.time.Duration

internal class NetworkInterceptor(
    snapshotMaxAge: Duration,
    private val preferences: SharedPreferences?,
    private val client: OkHttpClient,
) : Interceptor {
    private val snapshotMaxAgeMS = snapshotMaxAge.inWholeMilliseconds
    private val snapshotSemaphore = Semaphore(6)
    private val spnSnapshotSemaphore = Semaphore(12)

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
    private fun getTimestamp(archiveUrl: HttpUrl): String? = runBlocking {
        client.get(archiveUrl, Headers.Builder().build(), ensureSuccess = false)
    }.use { response ->
        response.header("Location")?.let {
            TIMESTAMP_REGEX.find(it)?.value
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
        credentials: String,
        body: FormBody,
    ): Response = runBlocking {
        client.post(
            SAVE_PREFIX,
            Headers
                .Builder()
                .add("Accept", "application/json")
                .add("Authorization", "LOW $credentials")
                .build(),
            body,
            ensureSuccess = false,
        )
    }

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

        return chain.proceed(
            request.newBuilder().url(
                getTimestamp(origUrl).let { timestamp ->
                    if (timestamp != null && !timestampIsExpired(timestamp)) {
                        getSnapshotUrl(timestamp, url)
                    } else {
                        snapshot(
                            url,
                            request.headers,
                        ) ?: getSnapshotUrl(
                            timestamp ?: throw IOException("Failed to archive page $url"),
                            url,
                        )
                    }
                },
            ).build(),
        )
    }

    private fun snapshotHelper(url: HttpUrl, headers: Headers): String? = rateLimit {
        val credentials = preferences?.getWaybackMachineS3CredentialsPref()
        if (credentials?.isNotEmpty() == true) {
            spnSnapshotSemaphore.withPermit {
                val id = spn(
                    credentials,
                    FormBody
                        .Builder()
                        .add("url", url.toString())
                        .add("capture_outlinks", "1")
                        .add("skip_first_archive", "1")
                        .add("if_not_archived_within", (snapshotMaxAgeMS / 1000).toString())
                        .add("js_behavior_timeout", "0")
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
            snapshotSemaphore.withPermit {
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

    private inline fun <R> snapshot(
        url: HttpUrl,
        headers: Headers,
        block: (String, HttpUrl) -> R?,
    ): R? = snapshotHelper(url, headers)?.let { timestamp ->
        block(timestamp, url)
    } ?: getRetryUrl(url).let { retryUrl ->
        // Retry snapshot with a new URL
        snapshotHelper(retryUrl, headers)?.let { timestamp ->
            block(timestamp, retryUrl)
        }
    }

    /**
     * Creates a snapshot and returns the snapshot URL
     */
    private fun snapshot(
        url: HttpUrl,
        headers: Headers,
    ): HttpUrl? = snapshot(
        url,
        headers,
        ::getSnapshotUrl,
    )
}
