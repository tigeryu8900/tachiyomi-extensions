import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "KeroScans"
    versionCode = 0
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"
    theme = "vinetheme"

    source {
        baseUrl = "https://keroscans.net"
        lang = "en"
    }
}

// TACH -->
dependencies {

    implementation(project(":lib:waybackmachineinterceptor"))
}
// <-- TACH
