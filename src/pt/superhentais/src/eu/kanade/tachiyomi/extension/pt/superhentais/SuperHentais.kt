package eu.kanade.tachiyomi.extension.pt.superhentais

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.lib.unpacker.Unpacker
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonString
import kotlinx.serialization.json.JsonElement
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

@Source
abstract class SuperHentais : KeiSource() {

    override fun Headers.Builder.configureHeaders(): Headers.Builder = set("Accept", ACCEPT_HTML)
        .set("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7")
        .set("Referer", baseUrl)
        .set("User-Agent", DESKTOP_USER_AGENT)

    override suspend fun getPopularManga(page: Int): MangasPage = getListing(page, DEFAULT_FILTERS)

    override suspend fun getLatestUpdates(page: Int): MangasPage = getListing(
        page,
        DEFAULT_FILTERS + ("filter_order" to LATEST_ORDER),
    )

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        if (query.isEmpty()) {
            val filterData = DEFAULT_FILTERS.toMutableMap()
            val includedGenres = mutableListOf<String>()
            val excludedGenres = mutableListOf<String>()
            filters.forEach { filter ->
                when (filter) {
                    is ContentFilter -> filterData["filter_type_content"] = filter.value
                    is LetterFilter -> filterData["filter_letter"] = filter.value
                    is StatusFilter -> filterData["filter_status"] = filter.value
                    is CensureFilter -> filterData["filter_censure"] = filter.value
                    is SortFilter -> filterData["filter_order"] = filter.value
                    is ExclusiveModeFilter -> filterData["filter_genre_model"] = if (filter.state) "yes" else "0"
                    is GenreFilter -> filter.state.forEach { genre ->
                        when {
                            genre.isIncluded() -> includedGenres += genre.id
                            genre.isExcluded() -> excludedGenres += genre.id
                        }
                    }
                    else -> Unit
                }
            }
            return getListing(page, filterData, includedGenres, excludedGenres)
        }

        if (page > 1) return MangasPage(emptyList(), false)

        val url = "$baseUrl/busca".toHttpUrl().newBuilder()
            .addQueryParameter("parametro", query)
            .addQueryParameter("search_type", "serie")
            .build()
        val document = client.get(url).use { it.asJsoup() }
        return MangasPage(parseMangaList(document), false)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (
            url.host != baseUrl.toHttpUrl().host ||
            url.pathSegments.size < 2 ||
            url.pathSegments.first() !in READABLE_CONTENT_PATHS
        ) {
            return null
        }

        val mangaUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(url.pathSegments[0])
            .addPathSegment(url.pathSegments[1])
            .build()
        val document = client.get(mangaUrl).use { it.asJsoup() }
        return parseMangaDetails(document).apply {
            setUrlWithoutDomain(mangaUrl.toString())
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val mangaUrl = getMangaUrl(manga)
        val document = client.get(mangaUrl).use { it.asJsoup() }
        return SMangaUpdate(
            manga = parseMangaDetails(document),
            chapters = parseChapterList(document, mangaUrl),
        )
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = getChapterUrl(chapter)
        val document = client.get(chapterUrl).use { it.asJsoup() }
        return document.select("div.capituloViewBox img[data-src]").mapIndexed { index, image ->
            Page(index, url = chapterUrl, imageUrl = stableImageUrl(image.absUrl("data-src")))
        }
    }

    override fun imageRequest(page: Page): Request = GET(
        page.imageUrl!!,
        headers.newBuilder().set("Referer", page.url).build(),
    )

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Os filtros abaixo são ignorados ao buscar por texto"),
        ContentFilter(),
        LetterFilter(),
        StatusFilter(),
        CensureFilter(),
        SortFilter(),
        GenreFilter(),
        ExclusiveModeFilter(),
    )

    private suspend fun getListing(
        page: Int,
        filterData: Map<String, String>,
        includedGenres: List<String> = emptyList(),
        excludedGenres: List<String> = emptyList(),
    ): MangasPage {
        val listingUrl = "$baseUrl/$LIST_PATH"
        val document = client.get(listingUrl).use { it.asJsoup() }
        val totalPages = document.selectFirst("select.pageSelect option:last-child")
            ?.attr("value")
            ?.toIntOrNull()
            ?: 1

        if (page > totalPages) return MangasPage(emptyList(), false)
        if (page == 1 && filterData == DEFAULT_FILTERS && includedGenres.isEmpty() && excludedGenres.isEmpty()) {
            return MangasPage(parseMangaList(document), totalPages > 1)
        }

        val encodedFilterData = filterData.entries.joinToString("&") { (key, value) -> "$key=$value" }
        val form = FormBody.Builder()
            .add("token", parseToken(document))
            .add("type_url", LIST_PATH)
            .add("page", page.toString())
            .add("limit", PAGE_SIZE.toString())
            .add("total_page", totalPages.toString())
            .add("type", "lista")
            .add("filters", PaginatorFilters(encodedFilterData, includedGenres, excludedGenres).toJsonString())
            .build()
        val result = client.post(
            "$baseUrl/inc/paginator.inc.php",
            ajaxHeaders(listingUrl),
            form,
        ).parseAs<PaginatorResponse>()
        if (result.codigo == 0) return MangasPage(emptyList(), false)

        val resultDocument = Jsoup.parseBodyFragment(result.body.joinToString(""), baseUrl)
        return MangasPage(parseMangaList(resultDocument), page < result.totalPage)
    }

    private fun parseMangaList(document: Document): List<SManga> = document.select("article.box_view.list div.grid_image.grid_image_vertical a[href]")
        .filter { element ->
            element.absUrl("href").toHttpUrlOrNull()?.pathSegments?.firstOrNull() in READABLE_CONTENT_PATHS
        }
        .map(::parseManga)

    private fun parseManga(element: Element): SManga {
        val image = element.selectFirst("img")!!
        val coverUrl = image.absUrl("data-src").ifEmpty { image.absUrl("src") }
        val categoryId = coverUrl.toHttpUrlOrNull()
            ?.pathSegments
            ?.lastOrNull()
            ?.substringBefore('-')
            ?.substringBefore('.')
        val chapterUrl = element.closest("article")
            ?.select("a[href]")
            ?.firstNotNullOfOrNull { link ->
                link.absUrl("href").takeIf { url ->
                    url.toHttpUrlOrNull()?.pathSegments?.lastOrNull()?.all(Char::isDigit) == true
                }
            }
        return SManga.create().apply {
            title = image.attr("alt")
            thumbnail_url = previewImageUrl(categoryId, chapterUrl, coverUrl)
            setUrlWithoutDomain(element.absUrl("href"))
        }
    }

    private fun parseMangaDetails(document: Document): SManga = SManga.create().apply {
        val info = document.selectFirst("ul.boxAnimeSobre")
        title = document.selectFirst("div.boxBarraInfo h1")!!.text()
        author = info?.selectFirst("li:contains(Autor) span")?.text()
        artist = info?.selectFirst("li:contains(Art:) span")?.text()
        genre = info?.select("li:contains(Genero) a, li:contains(Tags) a")
            ?.joinToString { it.text() }
        status = when (info?.selectFirst("li:contains(Conteúdo)")?.text()?.lowercase()) {
            null -> SManga.UNKNOWN
            else -> {
                val statusText = info.selectFirst("li:contains(Conteúdo)")!!.text().lowercase()
                when {
                    "completo" in statusText -> SManga.COMPLETED
                    "tradução" in statusText || "lançamento" in statusText || "upado" in statusText -> SManga.ONGOING
                    else -> SManga.UNKNOWN
                }
            }
        }
        description = document.selectFirst("p#sinopse")?.text()
        val coverUrl = document.selectFirst("span.boxAnimeImg img")?.absUrl("src")
        val categoryId = document.selectFirst("div#listaDeConteudo")?.attr("data-id-cat")
        val chapterUrl = document.selectFirst(CHAPTER_SELECTOR)?.selectFirst("a[href]")?.absUrl("href")
        thumbnail_url = previewImageUrl(categoryId, chapterUrl, coverUrl)
    }

    private fun previewImageUrl(categoryId: String?, chapterUrl: String?, coverUrl: String?): String? {
        val chapterId = chapterUrl?.toHttpUrlOrNull()
            ?.pathSegments
            ?.lastOrNull()
            ?.takeIf { it.all(Char::isDigit) }
        if (categoryId.isNullOrEmpty() || chapterId == null) return coverUrl

        // The dedicated cover CDN currently returns 404, while chapter images remain available.
        return "https://manga.${baseUrl.toHttpUrl().host}/img/manga/$categoryId/$chapterId/1.jpg"
    }

    private fun stableImageUrl(url: String): String {
        val parsedUrl = url.toHttpUrlOrNull() ?: return url
        val baseHost = baseUrl.toHttpUrl().host
        if (parsedUrl.host != "mangajpg.$baseHost") return url

        return parsedUrl.newBuilder()
            .host("manga.$baseHost")
            .build()
            .toString()
    }

    private suspend fun parseChapterList(document: Document, mangaUrl: String): List<SChapter> {
        val chapters = document.select(CHAPTER_SELECTOR).map(::parseChapter).toMutableList()
        val lastPage = document.selectFirst("select.pageSelect option:last-child")
            ?.attr("value")
            ?.toIntOrNull()
            ?: 1
        if (lastPage <= 1) return chapters.sortedByDescending { it.chapter_number }

        val categoryId = document.selectFirst("div#listaDeConteudo")!!.attr("data-id-cat")
        val token = parseToken(document)
        for (page in 2..lastPage) {
            val form = FormBody.Builder()
                .add("token", token)
                .add("id_cat", categoryId)
                .add("page", page.toString())
                .add("limit", CHAPTER_PAGE_SIZE.toString())
                .add("total_page", lastPage.toString())
                .add("order_video", "asc")
                .add("order_audio", "")
                .add("type", "book")
                .build()
            val result = client.post(
                "$baseUrl/inc/paginatorVideo.inc.php",
                ajaxHeaders(mangaUrl),
                form,
            ).parseAs<PaginatorResponse>()
            if (result.codigo == 0) break

            chapters += Jsoup.parseBodyFragment(result.body.joinToString(""), baseUrl)
                .select(CHAPTER_SELECTOR)
                .map(::parseChapter)
        }
        return chapters.sortedByDescending { it.chapter_number }
    }

    private fun parseChapter(element: Element): SChapter = SChapter.create().apply {
        val link = element.selectFirst("a[href]")!!
        name = link.text()
        chapter_number = element.selectFirst("span")?.text()?.toFloatOrNull() ?: -1f
        setUrlWithoutDomain(link.absUrl("href"))
    }

    private fun parseToken(document: Document): String {
        val packedScript = document.selectFirst("script:containsData(ajaxSetup)")!!.data()
        return Unpacker.unpack(packedScript)
            .substringAfter("token:\"")
            .substringBefore('"')
            .also { check(it.isNotEmpty()) { "Paginator token not found" } }
    }

    private fun ajaxHeaders(referer: String): Headers = headers.newBuilder()
        .set("Referer", referer)
        .set("Accept", ACCEPT_JSON)
        .set("X-Requested-With", "XMLHttpRequest")
        .build()

    companion object {
        private const val LIST_PATH = "hentai-manga"
        private const val PAGE_SIZE = 24
        private const val CHAPTER_PAGE_SIZE = 25
        private const val POPULAR_ORDER = "more_access"
        private const val LATEST_ORDER = "date-desc"
        private const val CHAPTER_SELECTOR = "div#listaDeConteudo div.top10Link, div.boxTop10 div.top10Link"
        private const val ACCEPT_HTML =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8"
        private const val ACCEPT_JSON = "application/json, text/javascript, */*; q=0.01"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        private val READABLE_CONTENT_PATHS = setOf("hentai-manga", "cartoon-ero", "hq-ero", "manhwa-ero")

        private val DEFAULT_FILTERS = linkedMapOf(
            "filter_display_view" to "lista",
            "filter_letter" to "0",
            "filter_order" to POPULAR_ORDER,
            "filter_type_content" to "5",
            "filter_genre_model" to "yes",
            "filter_status" to "0",
            "filter_size_start" to "0",
            "filter_size_final" to "0",
            "filter_date" to "0",
            "filter_date_ordem" to "0",
            "filter_censure" to "0",
            "filter_idade" to "",
            "filter_dub" to "0",
            "filter_viewed" to "0",
        )
    }
}
