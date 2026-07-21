package keiyoushi.lib.waybackmachineinterceptor

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import okhttp3.OkHttpClient
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

internal const val HOST = "web.archive.org"
internal const val SAVE_PREFIX = "https://$HOST/save/"
internal const val WEB_PREFIX = "https://$HOST/web/"
internal const val RANDOM_QUERY_PARAM = "__WaybackMachineInterceptor_RANDOM_QUERY_PARAM__"
internal val TIMESTAMP_REGEX = """(?<=://${Regex.escape(HOST)}/web/)\d{14}""".toRegex()
internal val DATE_FORMAT = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

fun OkHttpClient.Builder.useWaybackMachine(
    include: Regex = ".*".toRegex(),
    preferences: SharedPreferences? = null,
): OkHttpClient.Builder = apply {
    addInterceptor(ApplicationInterceptor(include, preferences))
    addNetworkInterceptor(NetworkInterceptor)
}

fun SharedPreferences.getUseWaybackMachinePref(): Boolean = getBoolean(
    PREF_USE_WAYBACK_MACHINE,
    false,
)

fun SharedPreferences.getWaybackMachineCaptureMaxAgePref(): String? = getString(
    PREF_WAYBACK_MACHINE_CAPTURE_MAX_AGE,
    "1d",
)

fun SharedPreferences.getWaybackMachineS3CredentialsPref(): String? = getString(
    PREF_WAYBACK_MACHINE_S3_CREDENTIALS,
    null,
)

fun setupWaybackMachinePreferenceScreen(screen: PreferenceScreen) {
    SwitchPreferenceCompat(screen.context).apply {
        key = PREF_USE_WAYBACK_MACHINE
        title = "Use Wayback Machine (web.archive.org)"
        summaryOff = "Requests are not redirected to web.archive.org."
        summaryOn = "Requests are redirected to web.archive.org."
        setDefaultValue(false)
    }.let(screen::addPreference)
    EditTextPreference(screen.context).apply {
        key = PREF_WAYBACK_MACHINE_CAPTURE_MAX_AGE
        title = "Wayback Machine capture max age"
        summary = "The maximum age a capture can be without attempting to create a new capture, defaults to 1d. It can be a space-separated list of components with a number followed by a unit (d, h, m, or s) where the last component can have a fractional part (i.e. 1h 0m 30.340s). It can also be in ISO-8601 Duration format (i.e. P1DT2H3M4.058S)."
        setDefaultValue("1d")
    }.let(screen::addPreference)
    EditTextPreference(screen.context).apply {
        key = PREF_WAYBACK_MACHINE_S3_CREDENTIALS
        title = "Wayback Machine S3 credentials (optional)"
        summary = "Using S3 credentials increases the API limit for making captures. Log into archive.org, grab the S3 access and secret keys at https://archive.org/account/s3.php, and enter them in this format: access_key:secret_key"
    }.let(screen::addPreference)
}

const val PREF_USE_WAYBACK_MACHINE = "pref_use_wayback_machine"
const val PREF_WAYBACK_MACHINE_CAPTURE_MAX_AGE = "pref_wayback_machine_capture_max_age"
const val PREF_WAYBACK_MACHINE_S3_CREDENTIALS = "pref_wayback_machine_s3_credentials"
