package keiyoushi.lib.waybackmachineinterceptor

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toDuration
import kotlin.time.toDurationUnit

internal const val HOST = "web.archive.org"
internal const val SAVE_PREFIX = "https://$HOST/save/"
internal const val WEB_PREFIX = "https://$HOST/web/"
internal const val RANDOM_QUERY_PARAM = "__WaybackMachineInterceptor_RANDOM_QUERY_PARAM__"
internal const val URL_CACHE_MAX_ENTRIES = 250
internal val TIMESTAMP_REGEX = """(?<=://${Regex.escape(HOST)}/web/)\d{14}""".toRegex()
internal val DATE_FORMAT = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

fun OkHttpClient.Builder.useWaybackMachine(
    include: Regex = ".*".toRegex(),
    snapshotMaxAge: Duration = 1.days,
    preferences: SharedPreferences? = null,
): OkHttpClient.Builder = apply {
    val client = build()
        .newBuilder()
        .readTimeout(60.seconds)
        .followRedirects(false)
        .build()
    addInterceptor(ApplicationInterceptor(include, preferences))
    addNetworkInterceptor(NetworkInterceptor(snapshotMaxAge, preferences, client))
}

fun OkHttpClient.Builder.useWaybackMachine(
    include: Regex = ".*".toRegex(),
    snapshotMaxAge: Long = 1L,
    timeUnit: TimeUnit = TimeUnit.DAYS,
    preferences: SharedPreferences? = null,
): OkHttpClient.Builder = useWaybackMachine(
    include,
    snapshotMaxAge.toDuration(timeUnit.toDurationUnit()),
    preferences,
)

fun SharedPreferences.getUseWaybackMachinePref(): Boolean = getBoolean(
    PREF_USE_WAYBACK_MACHINE,
    false,
)

fun SharedPreferences.getWaybackMachineS3CredentialsPref(): String? = getString(
    PREF_WAYBACK_MACHINE_S3_CREDENTIALS,
    null,
)

fun setupWaybackMachinePreferenceScreen(screen: PreferenceScreen) {
    SwitchPreferenceCompat(screen.context).apply {
        key = PREF_USE_WAYBACK_MACHINE
        title = "Use Wayback Machine (web.archive.org)"
        summaryOff = "Requests are not redirected to web.archive.org"
        summaryOn = "Requests are redirected to web.archive.org"
        setDefaultValue(false)
    }.let(screen::addPreference)
    EditTextPreference(screen.context).apply {
        key = PREF_WAYBACK_MACHINE_S3_CREDENTIALS
        title = "Wayback Machine S3 credentials (for higher API limits)"
        summary = "Log into archive.org, grab the S3 access and secret keys at https://archive.org/account/s3.php, and enter them in this format: access_key:secret_key"
    }.let(screen::addPreference)
}

const val PREF_USE_WAYBACK_MACHINE = "pref_use_wayback_machine"
const val PREF_WAYBACK_MACHINE_S3_CREDENTIALS = "pref_wayback_machine_s3_credentials"
val SAVE_URL = "https://web.archive.org/save/".toHttpUrl()
