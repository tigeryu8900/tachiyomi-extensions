package keiyoushi.utils

import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.fullType
import kotlin.collections.mutableMapOf

inline fun <reified T : Any> injektOrAddByKey(key: Any, crossinline block: () -> T): T = with(Injekt.registrar) {
    synchronized(this) {
        getInstanceOrElse(fullType<MutableMap<Any, Any>>().type) {
            mutableMapOf<Any, Any>().also { map ->
                try {
                    addSingleton(map)
                    return@also
                } catch (_: Throwable) {}
                try {
                    KoinPlatformTools.defaultContext().get().loadModules(
                        listOf(
                            module {
                                single { map }
                            },
                        ),
                    )
                    return@also
                } catch (_: Throwable) {}
                throw UnsupportedOperationException("Adding singletons is not supported")
            }
        }.getOrPut(key, block) as T
    }
}
