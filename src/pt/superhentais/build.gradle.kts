import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Super Hentais"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        baseUrl = "https://superhentais.com"
        lang = "pt-BR"
        id = 6434607482102119984L
    }

    deeplink {
        path("/hentai-manga/..*")
        path("/cartoon-ero/..*")
        path("/manhwa-ero/..*")
        path("/hq-ero/..*")
    }
}

dependencies {
    implementation(project(":lib:unpacker"))
}
