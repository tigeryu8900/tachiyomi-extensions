package eu.kanade.tachiyomi.extension.en.keroscans

import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.multisrc.vinetheme.VineTheme
import keiyoushi.annotation.Source
import keiyoushi.lib.waybackmachineinterceptor.setupWaybackMachinePreferenceScreen
import keiyoushi.lib.waybackmachineinterceptor.useWaybackMachine
import okhttp3.OkHttpClient

@Source
// TACH -->
abstract class Keroscans : VineTheme() {
    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = useWaybackMachine(
        """^\w+://keroscans\.net/.*""".toRegex(),
        preferences,
    )

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)
        setupWaybackMachinePreferenceScreen(screen)
    }
}
// <-- TACH
