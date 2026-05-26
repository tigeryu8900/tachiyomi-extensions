package keiyoushi.lib.waybackmachineinterceptor

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toDuration
import kotlin.time.toDurationUnit

fun OkHttpClient.Builder.useWaybackMachine(
    include: Regex = ".*".toRegex(),
    snapshotMaxAge: Duration = 1.days,
): OkHttpClient.Builder = this
    .readTimeout(60.seconds)
    .addInterceptor(WaybackMachineInterceptor(include, snapshotMaxAge))
    .followRedirects(false)

fun OkHttpClient.Builder.useWaybackMachine(
    include: Regex = ".*".toRegex(),
    snapshotMaxAge: Long = 1L,
    timeUnit: TimeUnit = TimeUnit.DAYS,
): OkHttpClient.Builder = this
    .useWaybackMachine(include, snapshotMaxAge.toDuration(timeUnit.toDurationUnit()))

fun SharedPreferences.getUseWaybackMachinePref(): Boolean = getBoolean(
    PREF_USE_WAYBACK_MACHINE,
    false,
)

fun setupWaybackMachinePreferenceScreen(screen: PreferenceScreen) {
    SwitchPreferenceCompat(screen.context).apply {
        key = PREF_USE_WAYBACK_MACHINE
        title = "Use Wayback Machine (web.archive.org)"
        summaryOff = "Requests are not redirected to web.archive.org"
        summaryOn = "Requests are redirected to web.archive.org"
        setDefaultValue(false)
    }.let(screen::addPreference)
}

const val PREF_USE_WAYBACK_MACHINE = "pref_use_wayback_machine"
val SAVE_URL = "https://web.archive.org/save/".toHttpUrl()
