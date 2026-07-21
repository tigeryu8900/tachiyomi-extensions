package keiyoushi.lib.waybackmachineinterceptor

import android.app.Application
import android.content.SharedPreferences
import android.widget.Toast
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.EOFException
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.IOException

internal class ApplicationInterceptor(
    private val include: Regex,
    private val preferences: SharedPreferences?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (preferences?.getUseWaybackMachinePref() == false ||
            url.host == HOST ||
            !include.matches(url.toString())
        ) {
            return chain.proceed(request)
        }

        val response = chain.proceed(
            request
                .newBuilder()
                .url("$WEB_PREFIX$url")
                .tag(SharedPreferences::class.java, preferences)
                .build(),
        )

        if (
            response.request.url.host == HOST &&
            !(
                response.networkResponse?.headers["Content-Encoding"]
                    ?: response.headers["Content-Encoding"]
                    ?: "identity"
                ).equals("identity", ignoreCase = true)
        ) {
            val responseBody = response.body

            try {
                // Force the underlying source to be evaluated and buffered
                responseBody.source().request(Long.MAX_VALUE)
                return response
            } catch (_: EOFException) {
                val salvagedBuffer = responseBody.source().buffer.clone()

                val recoveredBody = salvagedBuffer
                    .asResponseBody(
                        responseBody.contentType(),
                        salvagedBuffer.size,
                    )

                Toast.makeText(
                    Injekt.get<Application>(),
                    "Response body truncated, response recovered",
                    Toast.LENGTH_LONG,
                ).show()

                return response.newBuilder()
                    .body(recoveredBody)
                    .build()
            } catch (e: IOException) {
                throw e
            }
        }

        return response
    }
}
