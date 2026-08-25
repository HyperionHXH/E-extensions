import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "E-Hentai"
    versionCode = 15
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    source {
        lang = "en"
        baseUrl {
            mirrors(
                "E-Hentai" to "https://e-hentai.org",
                "ExHentai" to "https://exhentai.org",
            )
        }
    }
}
