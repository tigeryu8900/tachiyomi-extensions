plugins {
    id("io.github.tjokinen.android-bcv-bridge") version "0.2.0"
    alias(libs.plugins.android.library)
}

dependencies {
    //noinspection UseTomlInstead
    compileOnly("androidx.appcompat:appcompat:1.8.0")
    compileOnly("androidx.webkit:webkit:1.17.0")
    compileOnly(libs.bundles.common)
    compileOnly(libs.tachiyomi.lib.v16)
}

android {
    namespace = "eu.kanade.tachiyomi.stub"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }
}
