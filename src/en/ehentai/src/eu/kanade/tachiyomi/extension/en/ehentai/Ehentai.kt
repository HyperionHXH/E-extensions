package eu.kanade.tachiyomi.extension.en.ehentai

import android.content.SharedPreferences
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.addCookie
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import okhttp3.CacheControl
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@Source
abstract class Ehentai :
    KeiSource(),
    ConfigurableSource {

    override val supportsLatest = false

    private val preferences: SharedPreferences by getPreferencesLazy()
    private val prefs by lazy { EhentaiPreferences(preferences) }
    private val sessionCookieJar = EhentaiCookieJar()

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder {
        val refreshClient = network.client.newBuilder()
            .cookieJar(sessionCookieJar)
            .addLoginCookies()
            .configureProxy()
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()

        return cookieJar(sessionCookieJar)
            .addLoginCookies()
            .configureProxy()
            .protocols(listOf(Protocol.HTTP_1_1))
            .retryOnConnectionFailure(true)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(
                EhentaiInterceptor(prefs, refreshClient) { viewerUrl, imageUrl ->
                    imageUrlCache[viewerUrl] = imageUrl
                },
            )
    }

    private fun OkHttpClient.Builder.addLoginCookies(): OkHttpClient.Builder = apply {
        addCookie({ Constants.DOMAIN_EHENTAI }) { prefs.loginCookies }
        addCookie({ Constants.DOMAIN_EXHENTAI }) { prefs.loginCookies }
    }

    private fun OkHttpClient.Builder.configureProxy(): OkHttpClient.Builder = apply {
        val configuredProxy = parseProxyUrl(prefs.proxyUrl) ?: return@apply
        proxy(configuredProxy)
    }

    private fun parseProxyUrl(value: String): Proxy? {
        if (value.isBlank()) return null
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val type = when (uri.scheme?.lowercase()) {
            "http", "https" -> Proxy.Type.HTTP
            "socks", "socks5" -> Proxy.Type.SOCKS
            else -> return null
        }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        return Proxy(type, InetSocketAddress(host, port))
    }

    private val nextPageCursors = ConcurrentHashMap<String, String>()
    private val watchedSeenUrls = ConcurrentHashMap<String, MutableSet<String>>()
    private val imageUrlCache = ConcurrentHashMap<String, String>()
    private val lastPageRequestAt = AtomicLong(0L)
    private val sessionPrimedHosts = ConcurrentHashMap.newKeySet<String>()

    override suspend fun getPopularManga(page: Int): MangasPage {
        checkExhentaiAccess()
        val url = "$baseUrl/popular"
        val html = fetchPageHtmlWithRetry(url)
        val mangas = parseMangaList(Jsoup.parse(html, url)).onEach { it.url = relativeUrl(it.url) }
        return MangasPage(mangas, false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = throw UnsupportedOperationException("Not used")

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        checkExhentaiAccess()
        val appliedFilters = filters.ifEmpty { getFilterList() }
        val browseMode = appliedFilters.filterIsInstance<BrowseModeFilter>().firstOrNull()?.state ?: 0

        if (query.isBlank() && appliedFilters.hasNoActiveFilters()) {
            return getPopularManga(page)
        }

        return when (browseMode) {
            1 -> {
                checkFavoriteAccess()
                val favoriteBaseUrl = if (isExhentai()) baseUrl else Constants.EXHENTAI_BASE_URL
                val rootUrl = buildSearchParams(favoriteBaseUrl, query, appliedFilters).build().toString()
                fetchSearchPage(rootUrl, page) ?: MangasPage(emptyList(), false)
            }
            2 -> getWatchedManga(page, query, appliedFilters)
            else -> {
                val rootUrl = buildSearchParams(baseUrl, query, appliedFilters).build().toString()
                fetchSearchPage(rootUrl, page) ?: MangasPage(emptyList(), false)
            }
        }
    }

    private suspend fun getWatchedManga(page: Int, query: String, filters: FilterList): MangasPage {
        checkWatchedAccess()

        val languageIsActive = filters.filterIsInstance<LanguageFilter>().firstOrNull()?.state?.let { it > 0 } == true
        val reservedTerms = (if (query.isBlank()) 0 else 1) + (if (languageIsActive) 1 else 0)
        val chunkSize = (MAX_INCLUDED_TERMS - reservedTerms).coerceAtLeast(1)
        val watchedTags = prefs.watchedIncludeTags.mapNotNull(::exactTagTerm).distinct()
        val hiddenTags = prefs.watchedExcludeTags.mapNotNull(::canonicalTag).distinct()
        val excludedTerms = hiddenTags
            .mapNotNull(::exactTagTerm)
            .distinct()
            .take(MAX_EXCLUDED_TERMS)
            .map { "-$it" }

        val rootUrls = buildList {
            if (prefs.hasLoginCookie) {
                val accountQuery = (listOf(query) + excludedTerms).filter { it.isNotBlank() }.joinToString(" ")
                add(buildSearchParams(baseUrl, accountQuery, filters).build().toString())
            }
            watchedTags.chunked(chunkSize).forEach { chunk ->
                val watchedTerms = (chunk.map { "~$it" } + excludedTerms).joinToString(" ")
                add(buildSearchParams(baseUrl, query, filters, watchedTerms).build().toString())
            }
        }
        val pages = rootUrls.mapNotNull { fetchSearchPage(it, page) }
        val feedKey = rootUrls.joinToString("|")
        val seenUrls = if (page <= 1) {
            ConcurrentHashMap.newKeySet<String>().also { watchedSeenUrls[feedKey] = it }
        } else {
            watchedSeenUrls.computeIfAbsent(feedKey) { ConcurrentHashMap.newKeySet() }
        }
        val mangas = pages
            .flatMap { it.mangas }
            .distinctBy { it.url }
            .filterNot { manga -> mangaTags(manga).any { it in hiddenTags } }
            .filter { seenUrls.add(it.url) }
            .sortedByDescending { galleryId(it.url) }
        return MangasPage(mangas, pages.any { it.hasNextPage })
    }

    private suspend fun primeSession(url: String) {
        if (!prefs.hasLoginCookie) return
        val root = url.toHttpUrl().newBuilder().apply {
            encodedPath("/")
            query(null)
            fragment(null)
        }.build()
        val host = root.host
        if (!sessionPrimedHosts.add(host)) return
        try {
            val sessionUrl = root.newBuilder().addPathSegment("mytags").addQueryParameter("tagset", "1").build()
            val headers = Headers.Builder().add("Referer", root.toString()).build()
            client.get(sessionUrl.toString(), headers, CacheControl.FORCE_NETWORK).use { response ->
                val html = response.body.string()
                rejectLoginPage(response.request.url.toString(), html)
            }
        } catch (error: Exception) {
            sessionPrimedHosts.remove(host)
            throw error
        }
    }

    private suspend fun fetchSearchPage(rootUrl: String, page: Int): MangasPage? {
        val url = if (page <= 1) rootUrl else nextPageCursors[rootUrl] ?: return null
        var pageUrl = url
        var html = try {
            fetchPageHtmlWithRetry(url)
        } catch (error: Exception) {
            if (!url.contains("/favorites.php")) throw error
            val mirrorUrl = alternateMirrorUrl(url) ?: throw error
            pageUrl = mirrorUrl
            fetchPageHtmlWithRetry(mirrorUrl)
        }
        var document = Jsoup.parse(html, pageUrl)
        if (url.contains("/favorites.php") && document.selectFirst(".glink") == null) {
            val mirrorUrl = alternateMirrorUrl(pageUrl)
            if (mirrorUrl != null) {
                pageUrl = mirrorUrl
                html = fetchPageHtmlWithRetry(mirrorUrl)
                document = Jsoup.parse(html, pageUrl)
            }
        }
        val nextUrl = parseNextUrl(html)
        if (nextUrl == null) nextPageCursors.remove(rootUrl) else nextPageCursors[rootUrl] = nextUrl

        if (url.contains("/favorites.php")) {
            prefs.saveFavoriteCategoryNames(parseFavoriteCategoryNames(document))
        }
        val mangas = parseMangaList(document).onEach { it.url = relativeUrl(it.url) }
        return MangasPage(mangas, nextUrl != null)
    }

    private fun alternateMirrorUrl(url: String): String? {
        val parsed = url.toHttpUrl()
        val mirrorHost = when (parsed.host) {
            Constants.DOMAIN_EHENTAI -> Constants.DOMAIN_EXHENTAI
            Constants.DOMAIN_EXHENTAI -> Constants.DOMAIN_EHENTAI
            else -> return null
        }
        return parsed.newBuilder().host(mirrorHost).build().toString()
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!prefs.isSiteHost(url.host) || url.pathSegments.firstOrNull() != "g") return null
        val manga = SManga.create().apply { this.url = relativeUrl(url.toString()) }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false)
            .manga
            .apply { initialized = true }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        checkExhentaiAccess()
        val url = absoluteUrl(manga.url).withNoWarningUrl()
        val html = fetchPageHtmlWithRetry(url)
        val document = Jsoup.parse(html, url)
        val cleanUrl = relativeUrl(manga.url).substringBefore('?')
        val updatedManga = parseGalleryDetails(document, SManga.create().apply { this.url = cleanUrl })
        val chapter = SChapter.create().apply {
            name = "Full Gallery"
            chapter_number = 1f
            date_upload = parsePostedDate(document)
            scanlator = null
            this.url = cleanUrl
        }
        return SMangaUpdate(updatedManga, listOf(chapter))
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val galleryUrl = absoluteUrl(chapter.url).toHttpUrl()
        val viewerUrls = ArrayList<String>()
        var thumbnailPage = 0
        var expectedThumbnailPages: Int? = null

        while (thumbnailPage < (expectedThumbnailPages ?: Constants.MAX_THUMB_PAGES) &&
            thumbnailPage < Constants.MAX_THUMB_PAGES
        ) {
            val url = galleryUrl.newBuilder().apply {
                if (thumbnailPage == 0) {
                    addQueryParameter("nw", "always")
                } else {
                    addQueryParameter("p", thumbnailPage.toString())
                }
            }.build().toString()
            val html = fetchPageHtmlWithRetry(url)
            val document = Jsoup.parse(html, url)
            if (thumbnailPage == 0) {
                val pageCount = parsePageCount(document)
                expectedThumbnailPages = when {
                    pageCount > 0 -> (pageCount + Constants.THUMBNAILS_PER_PAGE - 1) /
                        Constants.THUMBNAILS_PER_PAGE
                    else -> parseThumbnailPageCount(document)
                }
            }
            val links = parseViewerLinks(document)
            val newLinks = links.filter { it !in viewerUrls }
            val expectedPages = expectedThumbnailPages
            if (expectedPages != null && thumbnailPage + 1 < expectedPages &&
                (links.isEmpty() || newLinks.isEmpty())
            ) {
                throw Exception(
                    "Gallery pagination returned no new thumbnails for page ${thumbnailPage + 1}; " +
                        "the site may be rate-limiting this request. Please retry after a short wait.",
                )
            }
            viewerUrls.addAll(newLinks)
            thumbnailPage++
            if (links.isEmpty()) break
        }

        return buildList {
            viewerUrls.forEachIndexed { index, viewerUrl ->
                val imageUrl = if (prefs.preResolveImages) resolveImageUrl(viewerUrl) else null
                add(Page(index, url = viewerUrl, imageUrl = imageUrl))
            }
        }
    }

    override suspend fun getImageUrl(page: Page): String = resolveImageUrl(page.url)

    private suspend fun resolveImageUrl(viewerUrl: String): String {
        imageUrlCache[viewerUrl]?.let { return it }
        val html = fetchPageHtmlWithRetry(viewerUrl)
        val document = Jsoup.parse(html, viewerUrl)
        val wantOriginal = prefs.wantOriginal && prefs.cookie.isNotEmpty()
        return parseImageUrl(document, wantOriginal).also { imageUrlCache[viewerUrl] = it }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = Headers.Builder()
            .add("Referer", page.url)
            .add("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            .add("Connection", "keep-alive")
            .add(EhentaiInterceptor.VIEWER_URL_HEADER, page.url)
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    override fun getHomeUrl(): String = baseUrl

    override fun getMangaUrl(manga: SManga): String = absoluteUrl(manga.url)

    override fun getChapterUrl(chapter: SChapter): String = absoluteUrl(chapter.url)

    override fun getFilterList(data: JsonElement?): FilterList = ehentaiFilterList(prefs.favoriteCategoryNames)

    override fun setupPreferenceScreen(screen: PreferenceScreen) = prefs.setupPreferenceScreen(screen)

    private suspend fun fetchPageHtml(url: String): String {
        checkExhentaiAccess()
        if (!url.contains("/mytags")) primeSession(url)
        throttlePageRequest()
        return client.get(url, pageHeaders(url), CacheControl.FORCE_NETWORK).use { response ->
            val finalUrl = response.request.url.toString()
            response.body.string().also { html -> rejectLoginPage(finalUrl, html) }
        }
    }

    private suspend fun fetchPageHtmlWithRetry(url: String): String {
        var lastError: Exception? = null
        for (attempt in 0 until 3) {
            try {
                return fetchPageHtml(url)
            } catch (error: Exception) {
                lastError = error
                val retryable = error is IOException || error.message?.contains("HTTP 429") == true ||
                    error.message?.contains("HTTP 5") == true
                if (!retryable || attempt == 2) break
                delay(350L * (attempt + 1))
            }
        }
        throw Exception(
            "Failed to fetch $url after 3 attempts (${lastError?.message}). Check the network, User-Agent and 登录 Cookie settings.",
            lastError,
        )
    }

    private fun rejectLoginPage(url: String, html: String) {
        val document = Jsoup.parse(html, url)
        if (html.contains("temporarily banned", ignoreCase = true) ||
            html.contains("excessive request rate", ignoreCase = true)
        ) {
            throw Exception(
                "E-Hentai 暂时限制了当前网络出口。请等待几十秒后重试，或更换 Clash 节点；不要连续刷新关注列表。",
            )
        }
        val redirectedToLogin = url.contains("bounce_login.php") ||
            document.title().contains("Login", ignoreCase = true) ||
            document.selectFirst("form[name=ipb_login_form]") != null
        if (!redirectedToLogin) return

        val endpoint = when {
            url.contains("favorites.php") -> "收藏夹"
            url.contains("/watched") || url.contains("/mytags") -> "关注标签"
            url.toHttpUrl().host == Constants.DOMAIN_EXHENTAI -> "ExHentai"
            else -> "E-Hentai 登录"
        }
        throw Exception(
            "${endpoint}请求返回了登录页。请确认三个 Cookie 来自同一浏览器、同一 Clash 网络出口，且没有复制末尾标点；" +
                "如果刚切换代理，请在当前出口重新登录并重新填写 ipb_member_id、ipb_pass_hash、igneous。",
        )
    }

    private fun pageHeaders(url: String): Headers {
        val parsed = url.toHttpUrl()
        return Headers.Builder()
            .add("Referer", "${parsed.scheme}://${parsed.host}/")
            .build()
    }

    private fun absoluteUrl(value: String): String {
        val normalized = normalizeUrl(value)
        val parsed = normalized.toHttpUrlOrNull()
        return parsed?.toString() ?: baseUrl.toHttpUrl().resolve(normalized)?.toString()
            ?: throw Exception("Invalid gallery URL: $value")
    }

    private fun relativeUrl(value: String): String {
        val parsed = normalizeUrl(value).toHttpUrlOrNull() ?: return value
        return buildString {
            append(parsed.encodedPath)
            parsed.encodedQuery?.let { append('?').append(it) }
            parsed.encodedFragment?.let { append('#').append(it) }
        }
    }

    private fun normalizeUrl(value: String): String = when {
        value.startsWith("https//", ignoreCase = true) -> "https://${value.substring(7)}"
        value.startsWith("http//", ignoreCase = true) -> "http://${value.substring(6)}"
        else -> value
    }

    private fun String.withNoWarningUrl(): String = toHttpUrl()
        .newBuilder()
        .setQueryParameter("nw", "always")
        .build()
        .toString()

    private fun checkExhentaiAccess() {
        if (isExhentai() && !prefs.hasLoginCookie) {
            throw Exception("exhentai.org requires ipb_member_id / ipb_pass_hash / igneous in the source settings.")
        }
    }

    private fun checkFavoriteAccess() {
        if (!prefs.hasLoginCookie) {
            throw Exception("Favorites require ipb_member_id / ipb_pass_hash / igneous in the source settings.")
        }
    }

    private fun checkWatchedAccess() {
        if (!prefs.hasLoginCookie && prefs.watchedIncludeTags.isEmpty()) {
            throw Exception(
                "Watched tags require a login cookie so the source can read E-Hentai My Tags, or local extra watched tags.",
            )
        }
    }

    private fun isExhentai(): Boolean = baseUrl.toHttpUrl().host == Constants.DOMAIN_EXHENTAI

    private suspend fun throttlePageRequest() {
        val intervalMs = prefs.requestIntervalMs
        if (intervalMs <= 0L) return
        while (true) {
            val now = System.currentTimeMillis()
            val last = lastPageRequestAt.get()
            val waitMs = intervalMs - (now - last)
            if (waitMs <= 0L) {
                if (lastPageRequestAt.compareAndSet(last, now)) return
            } else {
                delay(waitMs)
            }
        }
    }

    private fun galleryId(url: String): Long = url.substringAfter("/g/", "0").substringBefore('/').toLongOrNull() ?: 0L

    private fun mangaTags(manga: SManga): Set<String> = manga.genre
        ?.split(',')
        ?.mapNotNull(::canonicalTag)
        ?.toSet()
        .orEmpty()

    companion object {
        private const val MAX_INCLUDED_TERMS = 5
        private const val MAX_EXCLUDED_TERMS = 10
    }
}

private class EhentaiCookieJar : CookieJar {
    private val storedCookies = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val existing = storedCookies[url.host].orEmpty()
        val merged = (existing + cookies)
            .filter { it.expiresAt > System.currentTimeMillis() }
            .associateBy { it.name + "\u0000" + it.domain + "\u0000" + it.path }
            .values
            .toList()
        storedCookies[url.host] = merged
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> = storedCookies.values
        .asSequence()
        .flatten()
        .filter { it.matches(url) && it.expiresAt > System.currentTimeMillis() }
        .toList()
}
