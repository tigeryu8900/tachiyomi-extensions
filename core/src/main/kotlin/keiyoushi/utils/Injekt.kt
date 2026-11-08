package keiyoushi.utils

import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.fullType
import java.util.concurrent.atomic.AtomicReference
import kotlin.collections.mutableMapOf

inline fun <reified K : Any, reified V : Any> InjektRegistrar.getOrAddRegistry(): MutableMap<K, V> = synchronized(this) {
    getInstanceOrElse(fullType<MutableMap<K, V>>().type) {
        mutableMapOf<K, V>().also { map ->
            try {
                addSingleton(map)
                return@also
            } catch (_: Throwable) {
            }
            try {
                KoinPlatformTools.defaultContext().get().loadModules(
                    listOf(
                        module {
                            single { map }
                        },
                    ),
                )
                return@also
            } catch (_: Throwable) {
            }
            throw UnsupportedOperationException("Adding singletons is not supported")
        }
    }
}

inline fun <reified T : Any> injektOrAddByKey(key: Any, crossinline block: () -> T): T = with(Injekt.registrar) {
    synchronized(this) {
        getOrAddRegistry<Any, T>().getOrPut(key, block)
    }
}

inline fun <reified T : Any> injektOrUpdate(
    key: Any,
    version: Long,
    crossinline unregister: (origVal: T?) -> Unit = {},
    crossinline block: () -> T,
): T = run {
    val entry = injektOrAddByKey<AtomicReference<Pair<T?, Long>>>(key) { AtomicReference(null to Long.MIN_VALUE) }

    val (origVal, origVer) = entry.get()

    if (origVal == null || version > origVer) {
        synchronized(entry) {
            val (origVal, origVer) = entry.get()

            if (origVal == null || version > origVer) {
                block().also {
                    entry.set(it to version)
                    unregister(origVal)
                }
            } else {
                origVal
            }
        }
    } else {
        origVal
    }
}
