package eu.kanade.tachiyomi.extension.all.pixez

import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant

@Source
abstract class PixEz :
    KeiSource(),
    ConfigurableSource {

    private val preferences by getPreferencesLazy()
    private val api by lazy { PixivApi(client, preferences) { lang } }
    private val nextUrls = ConcurrentHashMap<String, String>()
    private val preferenceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun Headers.Builder.configureHeaders() = set("Referer", "$baseUrl/")
        .set("Accept", "application/json")

    override suspend fun getPopularManga(page: Int): MangasPage {
        if (!api.isLoggedIn) return webRanking(page)

        val type = preferences.getString(PREF_POPULAR, "manga") ?: "manga"
        val path = if (type == "illust") "/v1/illust/recommended" else "/v1/manga/recommended"
        return appIllustPage("popular:$type", page, path) {
            addQueryParameter("filter", "for_ios")
            addQueryParameter("include_ranking_label", "true")
        }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        if (!api.isLoggedIn) return webLatest(page)

        val restrict = preferences.getString(PREF_LATEST_RESTRICT, "public") ?: "public"
        return appIllustPage("latest:$restrict", page, "/v2/illust/follow") {
            addQueryParameter("restrict", restrict)
        }
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        parseShortcut(query)?.let { return MangasPage(listOf(fetchTarget(it)), false) }
        if (filters.accountAction != AccountAction.NONE) {
            if (page > 1) return MangasPage(emptyList(), false)
            return performAccountAction(filters)
        }

        return when (filters.feed) {
            Feed.SEARCH -> search(page, query, filters)
            Feed.RECOMMENDED_ILLUST -> appIllustPage("recommended:illust", page, "/v1/illust/recommended") {
                addQueryParameter("filter", "for_ios")
                addQueryParameter("include_ranking_label", "true")
            }
            Feed.RECOMMENDED_MANGA -> appIllustPage("recommended:manga", page, "/v1/manga/recommended") {
                addQueryParameter("filter", "for_ios")
                addQueryParameter("include_ranking_label", "true")
            }
            Feed.FOLLOWING_PUBLIC -> followingPage(page, "public")
            Feed.FOLLOWING_PRIVATE -> followingPage(page, "private")
            Feed.BOOKMARKS_PUBLIC -> bookmarkPage(page, filters, "public")
            Feed.BOOKMARKS_PRIVATE -> bookmarkPage(page, filters, "private")
            Feed.BOOKMARK_TAGS_PUBLIC -> bookmarkTagsPage(page, "public")
            Feed.BOOKMARK_TAGS_PRIVATE -> bookmarkTagsPage(page, "private")
            Feed.RANKING -> rankingPage(page, filters.ranking)
            Feed.USER_WORKS -> userWorksPage(page, filters)
            Feed.FOLLOWING_USERS -> userListPage(page, filters, following = true)
            Feed.RECOMMENDED_USERS -> userListPage(page, filters, following = false)
            Feed.WATCHLIST -> watchlistPage(page)
            Feed.RELATED -> relatedPage(page, filters)
            Feed.SERIES -> seriesPage(page, filters)
        }
    }

    override fun getFilterList(data: kotlinx.serialization.json.JsonElement?) = pixEzFilters()

    private suspend fun search(page: Int, query: String, filters: FilterList): MangasPage {
        if (filters.artworkId.isNotEmpty()) {
            if (page > 1) return MangasPage(emptyList(), false)
            return MangasPage(listOf(fetchTarget(Target.Artwork(filters.artworkId.toLong()))), false)
        }
        if (filters.userId.isNotEmpty()) return userWorksPage(page, filters)
        if (query.isBlank()) {
            return if (api.isLoggedIn) {
                appIllustPage("search:blank", page, "/v1/manga/recommended") {
                    addQueryParameter("filter", "for_ios")
                }
            } else {
                webLatest(page)
            }
        }
        if (!api.isLoggedIn) return webSearch(page, query, filters)

        val key = listOf(
            "search",
            query,
            filters.searchTarget.value,
            filters.sort.value,
            filters.aiMode.value,
            filters.startDate,
            filters.endDate,
            filters.minimumBookmarks,
            filters.maximumBookmarks,
            filters.workType.value,
        ).joinToString(":")
        return appIllustPage(key, page, "/v1/search/illust", filters.workType) {
            addQueryParameter("filter", "for_android")
            addQueryParameter("merge_plain_keyword_results", "true")
            addQueryParameter("word", query)
            addQueryParameter("search_target", filters.searchTarget.value)
            addQueryParameter("sort", filters.sort.value)
            filters.aiMode.value?.let { addQueryParameter("search_ai_type", it.toString()) }
            filters.startDate.takeIf(String::isNotEmpty)?.let { addQueryParameter("start_date", it) }
            filters.endDate.takeIf(String::isNotEmpty)?.let { addQueryParameter("end_date", it) }
            filters.minimumBookmarks.toIntOrNull()?.let { addQueryParameter("bookmark_num_min", it.toString()) }
            filters.maximumBookmarks.toIntOrNull()?.let { addQueryParameter("bookmark_num_max", it.toString()) }
        }
    }

    private suspend fun followingPage(page: Int, restrict: String) = appIllustPage("following:$restrict", page, "/v2/illust/follow") {
        addQueryParameter("restrict", restrict)
    }

    private suspend fun bookmarkPage(page: Int, filters: FilterList, restrict: String): MangasPage {
        val tag = filters.bookmarkTag
        return appIllustPage("bookmarks:$restrict:$tag", page, "/v1/user/bookmarks/illust", filters.workType) {
            addQueryParameter("user_id", api.userId.toString())
            addQueryParameter("restrict", restrict)
            tag.takeIf(String::isNotEmpty)?.let { addQueryParameter("tag", it) }
        }
    }

    private suspend fun bookmarkTagsPage(page: Int, restrict: String): MangasPage {
        val key = "bookmark-tags:$restrict"
        val result = appResponse(key, page, "/v1/user/bookmark-tags/illust") {
            addQueryParameter("user_id", api.userId.toString())
            addQueryParameter("restrict", restrict)
        }.parseAs<BookmarkTagsResponse>()
        saveNext(key, page, result.nextUrl)
        return MangasPage(result.tags.map { it.toSManga(restrict) }, result.nextUrl != null)
    }

    private suspend fun rankingPage(page: Int, ranking: RankingMode) = appIllustPage("ranking:${ranking.value}", page, "/v1/illust/ranking") {
        addQueryParameter("filter", "for_android")
        addQueryParameter("mode", ranking.value)
    }

    private suspend fun userWorksPage(page: Int, filters: FilterList): MangasPage {
        val userId = filters.userId.toLongOrNull()
            ?: throw IOException("Enter a numeric User ID")
        val type = filters.workType.value ?: "manga"
        return appIllustPage("user:$userId:$type", page, "/v1/user/illusts") {
            addQueryParameter("filter", "for_android")
            addQueryParameter("user_id", userId.toString())
            addQueryParameter("type", type)
        }
    }

    private suspend fun userListPage(
        page: Int,
        filters: FilterList,
        following: Boolean,
    ): MangasPage {
        val key: String
        val response = if (following) {
            val userId = filters.userId.toLongOrNull() ?: api.userId
            key = "following-users:$userId"
            appResponse(key, page, "/v1/user/following") {
                addQueryParameter("filter", "for_android")
                addQueryParameter("user_id", userId.toString())
                addQueryParameter("restrict", "public")
            }
        } else {
            key = "recommended-users"
            appResponse(key, page, "/v1/user/recommended") {
                addQueryParameter("filter", "for_android")
            }
        }
        val result = response.parseAs<UserPreviewResponse>()
        saveNext(key, page, result.nextUrl)
        return MangasPage(result.users.map { it.user.toSManga() }, result.nextUrl != null)
    }

    private suspend fun relatedPage(page: Int, filters: FilterList): MangasPage {
        val id = filters.artworkId.toLongOrNull()
            ?: throw IOException("Enter a numeric Artwork ID")
        return appIllustPage("related:$id", page, "/v2/illust/related", filters.workType) {
            addQueryParameter("filter", "for_android")
            addQueryParameter("illust_id", id.toString())
        }
    }

    private suspend fun watchlistPage(page: Int): MangasPage {
        val key = "watchlist"
        val result = appResponse(key, page, "/v1/watchlist/manga") {}
            .parseAs<WatchlistResponse>()
        saveNext(key, page, result.nextUrl)
        return MangasPage(result.series.map(WatchlistSeries::toSManga), result.nextUrl != null)
    }

    private suspend fun seriesPage(page: Int, filters: FilterList): MangasPage {
        val id = filters.artworkId.toLongOrNull()
            ?: throw IOException("Enter a numeric series ID")
        if (page > 1) return MangasPage(emptyList(), false)
        val series = fetchAppSeries(id, fetchAll = false)
        return MangasPage(listOf(series.detail.toSManga()), false)
    }

    private suspend fun appIllustPage(
        key: String,
        page: Int,
        path: String,
        type: WorkType = WorkType.ALL,
        configure: HttpUrl.Builder.() -> Unit = {},
    ): MangasPage {
        val result = appResponse(key, page, path, configure).parseAs<IllustListResponse>()
        saveNext(key, page, result.nextUrl)
        val mangas = result.illusts
            .asSequence()
            .filter { it.type != "ugoira" }
            .filter { type.value == null || it.type == type.value }
            .map { it.toSManga(groupSeries()) }
            .distinctBy(SManga::url)
            .toList()
        return MangasPage(mangas, result.nextUrl != null)
    }

    private suspend fun appResponse(
        key: String,
        page: Int,
        path: String,
        configure: HttpUrl.Builder.() -> Unit,
    ) = if (page == 1) {
        clearNext(key)
        api.get(path, configure)
    } else {
        nextUrls.remove("$key:$page")?.let { api.get(it) }
            ?: throw IOException("No next page is available")
    }

    private fun saveNext(key: String, page: Int, nextUrl: String?) {
        nextUrl?.let { nextUrls["$key:${page + 1}"] = it }
    }

    private fun clearNext(key: String) {
        nextUrls.keys.removeAll { it.startsWith("$key:") }
    }

    private suspend fun performAccountAction(filters: FilterList): MangasPage {
        val id = filters.actionId.toLongOrNull()
            ?: throw IOException("Enter a numeric action target ID")
        when (filters.accountAction) {
            AccountAction.BOOKMARK_PUBLIC, AccountAction.BOOKMARK_PRIVATE -> {
                val restrict = if (filters.accountAction == AccountAction.BOOKMARK_PRIVATE) "private" else "public"
                val values = mutableListOf("illust_id" to id.toString(), "restrict" to restrict)
                filters.actionTags.split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach { values += "tags[]" to it }
                api.post("/v2/illust/bookmark/add", values).close()
                return MangasPage(listOf(fetchTarget(Target.Artwork(id))), false)
            }
            AccountAction.REMOVE_BOOKMARK -> {
                api.post("/v1/illust/bookmark/delete", listOf("illust_id" to id.toString())).close()
                return MangasPage(listOf(fetchTarget(Target.Artwork(id))), false)
            }
            AccountAction.FOLLOW_PUBLIC, AccountAction.FOLLOW_PRIVATE -> {
                val restrict = if (filters.accountAction == AccountAction.FOLLOW_PRIVATE) "private" else "public"
                api.post("/v1/user/follow/add", listOf("user_id" to id.toString(), "restrict" to restrict)).close()
                return MangasPage(listOf(fetchTarget(Target.User(id))), false)
            }
            AccountAction.UNFOLLOW -> {
                api.post("/v1/user/follow/delete", listOf("user_id" to id.toString())).close()
                return MangasPage(listOf(fetchTarget(Target.User(id))), false)
            }
            AccountAction.WATCH_SERIES, AccountAction.UNWATCH_SERIES -> {
                val path = if (filters.accountAction == AccountAction.WATCH_SERIES) {
                    "/v1/watchlist/manga/add"
                } else {
                    "/v1/watchlist/manga/delete"
                }
                api.post(path, listOf("series_id" to id.toString())).close()
                return MangasPage(listOf(fetchTarget(Target.Series(id))), false)
            }
            AccountAction.NONE -> return MangasPage(emptyList(), false)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? = Target.fromUrl(url)?.let { fetchTarget(it) }

    private suspend fun fetchTarget(target: Target): SManga = when (target) {
        is Target.Artwork -> fetchIllust(target.id).toSManga(groupSeries = false)
        is Target.User -> fetchUser(target.id).toSManga()
        is Target.BookmarkTag -> target.toSManga()
        is Target.Series -> if (api.isLoggedIn) {
            fetchAppSeries(target.id, fetchAll = false).detail.toSManga(target.authorId)
        } else {
            val details = webGet<WebSeriesDetails>("/touch/ajax/illust/series/${target.id}").series
            SManga.create().apply {
                url = Target.Series(target.id, details.userId?.toLongOrNull() ?: target.authorId).value
                title = details.title
                description = details.caption
            }
        }
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = when (val target = Target.fromValue(manga.url) ?: throw IOException("Unsupported PixEz entry")) {
        is Target.Artwork -> updateArtwork(manga, chapters, target, fetchDetails, fetchChapters)
        is Target.Series -> updateSeries(manga, chapters, target, fetchDetails, fetchChapters)
        is Target.User -> updateUser(manga, chapters, target, fetchDetails, fetchChapters)
        is Target.BookmarkTag -> updateBookmarkTag(manga, chapters, target, fetchDetails, fetchChapters)
    }

    private suspend fun updateArtwork(
        manga: SManga,
        chapters: List<SChapter>,
        target: Target.Artwork,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val illust = fetchIllust(target.id)
        val updated = if (fetchDetails) illust.toDetailedSManga(groupSeries = false) else manga
        val updatedChapters = if (fetchChapters) listOf(illust.toSChapter(1f, includeId = true)) else chapters
        return SMangaUpdate(updated, updatedChapters)
    }

    private suspend fun updateBookmarkTag(
        manga: SManga,
        chapters: List<SChapter>,
        target: Target.BookmarkTag,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val illusts = if (fetchDetails || fetchChapters) {
            fetchAllBookmarks(target.tag, target.restrict)
        } else {
            emptyList()
        }
        val updated = if (fetchDetails) {
            target.toSManga().apply {
                thumbnail_url = illusts.firstOrNull()?.imageUrls?.large
                description = "Bookmarks: ${illusts.size} | Visibility: ${target.restrict}"
            }
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) illusts.toChapters() else chapters
        return SMangaUpdate(updated, updatedChapters)
    }

    private suspend fun updateSeries(
        manga: SManga,
        chapters: List<SChapter>,
        target: Target.Series,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (api.isLoggedIn) {
            val result = fetchAppSeries(target.id, fetchAll = fetchChapters)
            val updated = if (fetchDetails) {
                result.detail.toSManga(target.authorId).apply {
                    thumbnail_url = thumbnail_url ?: result.illusts.firstOrNull()?.imageUrls?.large
                    genre = result.illusts.flatMap(Illust::tags).distinctBy(PixivTag::name).joinToString { it.name }
                }
            } else {
                manga
            }
            val updatedChapters = if (fetchChapters) result.illusts.toChapters() else chapters
            return SMangaUpdate(updated, updatedChapters)
        }

        val details = webGet<WebSeriesDetails>("/touch/ajax/illust/series/${target.id}").series
        val illusts = if (fetchChapters || fetchDetails) fetchWebSeriesContents(target.id) else emptyList()
        val updated = if (fetchDetails) {
            manga.apply {
                title = details.title
                description = details.caption
                author = illusts.firstOrNull()?.author?.userName
                artist = author
                thumbnail_url = illusts.firstOrNull()?.url
                genre = illusts.flatMap(WebIllust::tags).distinct().joinToString()
            }
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) illusts.toWebChapters() else chapters
        return SMangaUpdate(updated, updatedChapters)
    }

    private suspend fun updateUser(
        manga: SManga,
        chapters: List<SChapter>,
        target: Target.User,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updated = if (fetchDetails) fetchUser(target.id).toSManga() else manga
        val updatedChapters = if (fetchChapters) {
            if (api.isLoggedIn) {
                (fetchAllUserWorks(target.id, "manga") + fetchAllUserWorks(target.id, "illust"))
                    .distinctBy(Illust::id)
                    .sortedByDescending(Illust::createDate)
                    .toChapters()
            } else {
                fetchWebUserWorks(target.id).toWebChapters()
            }
        } else {
            chapters
        }
        return SMangaUpdate(updated, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val id = Target.fromValue(chapter.url) as? Target.Artwork
            ?: throw IOException("Unsupported PixEz chapter")
        val pages = webGet<List<WebPage>>("/ajax/illust/${id.id}/pages")
        val quality = preferences.getString(PREF_IMAGE_QUALITY, "original") ?: "original"
        return pages.mapIndexed { index, page ->
            val imageUrl = when (quality) {
                "medium" -> page.urls.small ?: page.urls.regular ?: page.urls.original
                "large" -> page.urls.regular ?: page.urls.original
                else -> page.urls.original ?: page.urls.regular ?: page.urls.small
            } ?: throw IOException("Pixiv did not return an image URL")
            Page(index, imageUrl = imageUrl)
        }
    }

    private suspend fun fetchIllust(id: Long): Illust {
        if (api.isLoggedIn) {
            return api.get("/v1/illust/detail") {
                addQueryParameter("filter", "for_android")
                addQueryParameter("illust_id", id.toString())
            }.parseAs<IllustDetailResponse>().illust
        }
        return webGet<WebIllustDetails>("/touch/ajax/illust/details?illust_id=$id").illust
            ?.toIllust()
            ?: throw IOException("Pixiv artwork $id was not found")
    }

    private suspend fun fetchUser(id: Long): PixivUser {
        if (api.isLoggedIn) {
            return api.get("/v1/user/detail") {
                addQueryParameter("filter", "for_android")
                addQueryParameter("user_id", id.toString())
            }.parseAs<UserDetailResponse>().user
        }
        val user = webGet<WebUserInfo>("/ajax/user/$id?full=1")
        return PixivUser(
            id = id,
            name = user.name,
            account = user.userId,
            profileImages = ProfileImages(user.imageBig ?: user.image.orEmpty()),
            comment = user.comment,
        )
    }

    private suspend fun fetchAppSeries(id: Long, fetchAll: Boolean): SeriesResult {
        val first = api.get("/v1/illust/series") {
            addQueryParameter("illust_series_id", id.toString())
        }.parseAs<SeriesResponse>()
        val illusts = buildList {
            first.firstIllust?.let(::add)
            addAll(first.illusts)
        }.distinctBy(Illust::id).toMutableList()
        var next = first.nextUrl
        while (fetchAll && next != null) {
            val page = api.get(next).parseAs<IllustListResponse>()
            illusts += page.illusts.filter { incoming -> illusts.none { it.id == incoming.id } }
            next = page.nextUrl
        }
        return SeriesResult(first.detail, illusts)
    }

    private suspend fun fetchAllUserWorks(userId: Long, type: String): List<Illust> {
        val illusts = mutableListOf<Illust>()
        var next: String? = null
        do {
            val page = if (next == null) {
                api.get("/v1/user/illusts") {
                    addQueryParameter("filter", "for_android")
                    addQueryParameter("user_id", userId.toString())
                    addQueryParameter("type", type)
                }
            } else {
                api.get(next)
            }.parseAs<IllustListResponse>()
            illusts += page.illusts
            next = page.nextUrl
        } while (next != null)
        return illusts
    }

    private suspend fun fetchAllBookmarks(tag: String, restrict: String): List<Illust> {
        val illusts = mutableListOf<Illust>()
        var next: String? = null
        do {
            val page = if (next == null) {
                api.get("/v1/user/bookmarks/illust") {
                    addQueryParameter("user_id", api.userId.toString())
                    addQueryParameter("restrict", restrict)
                    addQueryParameter("tag", tag)
                }
            } else {
                api.get(next)
            }.parseAs<IllustListResponse>()
            illusts += page.illusts.filter { it.type != "ugoira" }
            next = page.nextUrl
        } while (next != null)
        return illusts.distinctBy(Illust::id)
    }

    private suspend fun webRanking(page: Int): MangasPage {
        val rankings = webGet<WebRankings>("/touch/ajax/ranking/illust?mode=daily&type=manga&page=$page").ranking
        if (rankings.isEmpty()) return MangasPage(emptyList(), false)
        val url = "$baseUrl/touch/ajax/illust/details/many".toHttpUrl().newBuilder().apply {
            addQueryParameter("lang", lang)
            rankings.forEach { addQueryParameter("illust_ids[]", it.illustId) }
        }.build()
        val result = client.get(url).parseAs<WebEnvelope<WebIllustsDetails>>().body
            ?: throw IOException("Pixiv did not return ranking details")
        return webMangasPage(result.illusts, hasNext = true)
    }

    private suspend fun webLatest(page: Int): MangasPage = webMangasPage(
        webGet<WebResults>("/touch/ajax/latest?type=manga&p=$page").illusts,
        hasNext = true,
    )

    private suspend fun webSearch(page: Int, query: String, filters: FilterList): MangasPage {
        val url = "$baseUrl/touch/ajax/search/illusts".toHttpUrl().newBuilder()
            .addQueryParameter("lang", lang)
            .addQueryParameter("word", query)
            .addQueryParameter("s_mode", if (filters.searchTarget == SearchTarget.EXACT) "s_tag_full" else "s_tag")
            .addQueryParameter("p", page.toString())
            .apply {
                filters.workType.value?.let { addQueryParameter("type", it) }
                filters.startDate.takeIf(String::isNotEmpty)?.let { addQueryParameter("scd", it) }
                filters.endDate.takeIf(String::isNotEmpty)?.let { addQueryParameter("ecd", it) }
            }
            .build()
        val result = client.get(url).parseAs<WebEnvelope<WebResults>>().body
            ?: throw IOException("Pixiv search failed")
        return webMangasPage(result.illusts, result.illusts.isNotEmpty())
    }

    private fun webMangasPage(illusts: List<WebIllust>, hasNext: Boolean): MangasPage {
        val mangas = illusts.asSequence()
            .filter { it.isAd != 1 && it.type != "ugoira" }
            .map { it.toSManga(groupSeries()) }
            .distinctBy(SManga::url)
            .toList()
        return MangasPage(mangas, hasNext && mangas.isNotEmpty())
    }

    private suspend inline fun <reified T> webGet(path: String): T {
        val url = baseUrl.toHttpUrl().newBuilder(path)!!
            .addQueryParameter("lang", lang)
            .build()
        val envelope = client.get(url).parseAs<WebEnvelope<T>>()
        if (envelope.error || envelope.body == null) {
            throw IOException(envelope.message.ifEmpty { "Pixiv request failed" })
        }
        return envelope.body
    }

    private suspend fun fetchWebSeriesContents(seriesId: Long): List<WebIllust> {
        val illusts = mutableListOf<WebIllust>()
        var lastOrder = 0
        while (true) {
            val page = webGet<WebSeriesContents>(
                "/touch/ajax/illust/series_content/$seriesId?last_order=$lastOrder",
            ).illusts
            if (page.isEmpty()) break
            illusts += page
            lastOrder += page.size
        }
        return illusts
    }

    private suspend fun fetchWebUserWorks(userId: Long): List<WebIllust> {
        val illusts = mutableListOf<WebIllust>()
        var page = 1
        while (true) {
            val result = webGet<WebResults>("/touch/ajax/user/illusts?id=$userId&p=$page").illusts
            if (result.isEmpty()) break
            illusts += result
            page++
        }
        return illusts
    }

    private fun Illust.toDetailedSManga(groupSeries: Boolean): SManga = toSManga(groupSeries).apply {
        description = this@toDetailedSManga.description()
        genre = tags.joinToString { it.translatedName ?: it.name }
        status = SManga.COMPLETED
        update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
    }

    private fun Illust.toSChapter(number: Float, includeId: Boolean = false): SChapter = SChapter.create().apply {
        url = Target.Artwork(id).value
        name = if (includeId) "$id - $title" else title
        date_upload = Instant.tryParse(createDate)
        chapter_number = number
    }

    private fun List<Illust>.toChapters(): List<SChapter> = mapIndexed { index, illust ->
        illust.toSChapter((size - index).toFloat())
    }

    private fun List<WebIllust>.toWebChapters(): List<SChapter> = mapIndexed { index, illust ->
        SChapter.create().apply {
            url = Target.Artwork(illust.id.toLong()).value
            name = illust.title
            date_upload = illust.uploadTimestamp?.times(1000) ?: 0
            chapter_number = (size - index).toFloat()
        }
    }

    private fun WebIllust.toIllust() = Illust(
        id = id.toLong(),
        title = title,
        type = type ?: "illust",
        imageUrls = ImageUrls(url.orEmpty(), url.orEmpty(), url.orEmpty()),
        caption = comment.orEmpty(),
        user = PixivUser(
            id = author?.userId?.toLongOrNull() ?: 0,
            name = author?.userName.orEmpty(),
            account = "",
            profileImages = ProfileImages(""),
        ),
        tags = tags.map { PixivTag(it) },
        createDate = uploadTimestamp?.let { java.time.Instant.ofEpochSecond(it).toString() },
        series = series?.let { IllustSeries(it.id.toLong(), it.title) },
    )

    private fun parseShortcut(query: String): Target? = when {
        query.startsWith("aid:") -> query.removePrefix("aid:").toLongOrNull()?.let(Target::Artwork)
        query.startsWith("user:") -> query.removePrefix("user:").toLongOrNull()?.let(Target::User)
        query.startsWith("sid:") -> query.removePrefix("sid:").toLongOrNull()?.let { Target.Series(it) }
        else -> null
    }

    override fun getMangaUrl(manga: SManga): String = when (val target = Target.fromValue(manga.url)) {
        is Target.Artwork -> "$baseUrl/artworks/${target.id}"
        is Target.User -> "$baseUrl/users/${target.id}"
        is Target.Series -> target.authorId?.let { "$baseUrl/user/$it/series/${target.id}" }
            ?: "$baseUrl/series/${target.id}"
        is Target.BookmarkTag -> baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("users")
            .addPathSegment(api.userId.toString())
            .addPathSegment("bookmarks")
            .addPathSegment("artworks")
            .addPathSegment(target.tag)
            .build()
            .toString()
        null -> baseUrl
    }

    override fun getChapterUrl(chapter: SChapter): String {
        val target = Target.fromValue(chapter.url) as? Target.Artwork ?: return baseUrl
        return "$baseUrl/artworks/${target.id}"
    }

    private fun groupSeries() = preferences.getBoolean(PREF_GROUP_SERIES, true)

    private fun Target.BookmarkTag.toSManga(): SManga = SManga.create().apply {
        url = value
        title = tag.ifEmpty { "Untagged" }
        description = "Pixiv bookmarks | Visibility: $restrict"
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = PixivApi.PREF_REFRESH_TOKEN
            title = "Pixiv refresh token"
            summary = if (api.userName != null) "Logged in as ${api.userName}" else "Not logged in"
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                it.isSingleLine = true
            }
            setOnPreferenceChangeListener { preference, _ ->
                preferences.edit()
                    .remove(PixivApi.PREF_ACCESS_TOKEN)
                    .remove(PixivApi.PREF_TOKEN_EXPIRY)
                    .remove(PixivApi.PREF_USER_ID)
                    .remove(PixivApi.PREF_USER_NAME)
                    .apply()
                preference.summary = "Token changed; verify login"
                true
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_ACCOUNT_ACTION
            title = "Account"
            entries = arrayOf("Verify login", "Log out")
            entryValues = arrayOf("verify", "logout")
            summary = api.userName?.let { "Logged in as $it" } ?: "Not verified"
            setOnPreferenceChangeListener { preference, value ->
                if (value == "logout") {
                    api.clearLogin()
                    preference.summary = "Not logged in"
                    Toast.makeText(screen.context, "PixEz login cleared", Toast.LENGTH_SHORT).show()
                } else {
                    preference.summary = "Verifying..."
                    preferenceScope.launch {
                        val result = runCatching { api.verifyLogin() }
                        withContext(Dispatchers.Main) {
                            preference.summary = result.fold(
                                onSuccess = { "Logged in as $it" },
                                onFailure = { it.message ?: "Login failed" },
                            )
                            Toast.makeText(screen.context, preference.summary, Toast.LENGTH_LONG).show()
                        }
                    }
                }
                false
            }
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_IMAGE_QUALITY
            title = "Image quality"
            entries = arrayOf("Medium", "Large", "Original")
            entryValues = arrayOf("medium", "large", "original")
            setDefaultValue("original")
            summary = "%s"
        }.also(screen::addPreference)

        SwitchPreferenceCompat(screen.context).apply {
            key = PREF_GROUP_SERIES
            title = "Group works by series"
            setDefaultValue(true)
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_POPULAR
            title = "Popular feed"
            entries = arrayOf("Recommended manga", "Recommended illustrations")
            entryValues = arrayOf("manga", "illust")
            setDefaultValue("manga")
            summary = "%s"
        }.also(screen::addPreference)

        ListPreference(screen.context).apply {
            key = PREF_LATEST_RESTRICT
            title = "Following feed"
            entries = arrayOf("Public follows", "Private follows")
            entryValues = arrayOf("public", "private")
            setDefaultValue("public")
            summary = "%s"
        }.also(screen::addPreference)
    }

    private class SeriesResult(
        val detail: SeriesDetail,
        val illusts: List<Illust>,
    )

    companion object {
        private const val PREF_IMAGE_QUALITY = "image_quality"
        private const val PREF_GROUP_SERIES = "group_series"
        private const val PREF_POPULAR = "popular_feed"
        private const val PREF_LATEST_RESTRICT = "latest_restrict"
        private const val PREF_ACCOUNT_ACTION = "account_action"
    }
}
