plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)

    alias(kei.plugins.android.base)
    alias(kei.plugins.spotless)
}

android {
    namespace = "keiyoushi.core"

    buildFeatures {
        resValues = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // TACH -->
    implementation("androidx.webkit:webkit:1.17.0") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    }
    //noinspection UseTomlInstead
    compileOnly("io.insert-koin:koin-core:4.2.2")
    compileOnly(project(":stub"))
    // <-- TACH

    compileOnly(libs.bundles.common)
    compileOnly(libs.tachiyomi.lib.v16)

    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
}
