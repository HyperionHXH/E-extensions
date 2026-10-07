import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

android {
    sourceSets.named("test") {
        java.directories.clear()
        kotlin.directories.clear()
        kotlin.directories.add("test")
    }
}

dependencies {
    testImplementation(libs.bundles.common)
    testImplementation(libs.tachiyomi.lib.v16)
    testImplementation(libs.junit)
}

tasks.matching { it.name == "kspDebugUnitTestKotlin" }.configureEach {
    // Source metadata is generated for the main variant, not for test fixtures.
    enabled = false
}

keiyoushi {
    name = "Komiic"
    versionCode = 13
    contentWarning = ContentWarning.MIXED
    libVersion = "1.6"

    source {
        lang = "zh"

        baseUrl {
            mirrors(
                "https://komiic.com",
                "https://komiic.cc",
            )
        }
    }

    deeplink {
        host("komiic.com")
        host("komiic.cc")
        path("/comic/..*")
    }
}
