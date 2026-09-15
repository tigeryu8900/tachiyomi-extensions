package keiyoushi.utils

import org.koin.dsl.module
import org.koin.mp.KoinPlatformTools
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.TypeReference
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.api.fullType
import uy.kohesive.injekt.api.getOrElse
import kotlin.Pair

inline fun <reified ID : Any, reified T : Any> injektOnce(
    id: TypeReference<ID> = fullType<ID>(),
    crossinline block: () -> T,
): T = with(Injekt.registrar) {
    synchronized(this) {
        getOrElse {
            (id to block()).also { result ->
                try {
                    addSingleton<Pair<TypeReference<ID>, T>>(result)
                    return@also
                } catch (_: Throwable) {}
                try {
                    KoinPlatformTools.defaultContext().get().loadModules(
                        listOf(
                            module {
                                single<Pair<TypeReference<ID>, T>> { result }
                            },
                        ),
                    )
                    return@also
                } catch (_: Throwable) {}
            }
        }.second
    }
}
