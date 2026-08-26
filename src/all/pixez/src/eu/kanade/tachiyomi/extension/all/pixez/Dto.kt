package eu.kanade.tachiyomi.extension.all.pixez

import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.jsoup.Jsoup

@Serializable
internal class OAuthEnvelope(
    val response: OAuthResponse,
)

@Serializable
internal class OAuthResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long,
    @SerialName("refresh_token") val refreshToken: String,
    val user: OAuthUser,
)

@Serializable
internal class OAuthUser(
    val id: String,
    val name: String,
)

@Serializable
internal class IllustListResponse(
    val illusts: List<Illust> = emptyList(),
    @SerialName("next_url") val nextUrl: String? = null,
)

@Serializable
internal class RecommendationResponse(
    val illusts: List<Illust> = emptyList(),
    @SerialName("ranking_illusts") val rankingIllusts: List<Illust> = emptyList(),
    @SerialName("next_url") val nextUrl: String? = null,
)

@Serializable
internal class IllustDetailResponse(
    val illust: Illust,
)

@Serializable
internal class Illust(
    val id: Long,
    val title: String,
    val type: String,
    @SerialName("image_urls") val imageUrls: ImageUrls,
    val caption: String = "",
    val user: PixivUser,
    val tags: List<PixivTag> = emptyList(),
    @SerialName("create_date") val createDate: String? = null,
    @SerialName("page_count") val pageCount: Int = 1,
    @SerialName("x_restrict") val xRestrict: Int = 0,
    @SerialName("meta_single_page") val metaSinglePage: MetaSinglePage? = null,
    @SerialName("meta_pages") val metaPages: List<MetaPage> = emptyList(),
    @SerialName("total_view") val totalViews: Long = 0,
    @SerialName("total_bookmarks") val totalBookmarks: Long = 0,
    @SerialName("is_bookmarked") val isBookmarked: Boolean = false,
    @SerialName("illust_ai_type") val aiType: Int = 0,
    val series: IllustSeries? = null,
) {
    fun toSManga(groupSeries: Boolean): SManga = SManga.create().apply {
        if (groupSeries && series != null) {
            url = Target.Series(series.id, user.id).value
            title = series.title
        } else {
            url = Target.Artwork(id).value
            title = this@Illust.title
        }
        thumbnail_url = imageUrls.large
        author = user.name
        artist = user.name
    }

    fun description(): String = buildString {
        val text = Jsoup.parseBodyFragment(caption).text()
        if (text.isNotEmpty()) append(text).append("\n\n")
        append("Views: ").append(totalViews)
        append(" | Bookmarks: ").append(totalBookmarks)
        if (isBookmarked) append(" | Bookmarked")
        if (user.isFollowed == true) append(" | Following creator")
        if (aiType == 2) append(" | AI-generated")
    }
}

@Serializable
internal class ImageUrls(
    @SerialName("square_medium") val squareMedium: String,
    val medium: String,
    val large: String,
)

@Serializable
internal class MetaSinglePage(
    @SerialName("original_image_url") val original: String? = null,
)

@Serializable
internal class MetaPage(
    @SerialName("image_urls") val imageUrls: PageImageUrls,
)

@Serializable
internal class PageImageUrls(
    val medium: String,
    val large: String,
    val original: String,
)

@Serializable
internal class PixivTag(
    val name: String,
    @SerialName("translated_name") val translatedName: String? = null,
)

@Serializable
internal class PixivUser(
    val id: Long,
    val name: String,
    val account: String,
    @SerialName("profile_image_urls") val profileImages: ProfileImages,
    val comment: String? = null,
    @SerialName("is_followed") val isFollowed: Boolean? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = Target.User(id).value
        title = name
        author = name
        artist = name
        thumbnail_url = profileImages.medium
        description = comment
    }
}

@Serializable
internal class ProfileImages(
    val medium: String,
)

@Serializable
internal class IllustSeries(
    val id: Long,
    val title: String,
)

@Serializable
internal class UserDetailResponse(
    val user: PixivUser,
)

@Serializable
internal class UserPreviewResponse(
    @SerialName("user_previews") val users: List<UserPreview> = emptyList(),
    @SerialName("next_url") val nextUrl: String? = null,
)

@Serializable
internal class UserPreview(
    val user: PixivUser,
    val illusts: List<Illust> = emptyList(),
)

@Serializable
internal class SeriesResponse(
    @SerialName("illust_series_detail") val detail: SeriesDetail,
    @SerialName("illust_series_first_illust") val firstIllust: Illust? = null,
    val illusts: List<Illust> = emptyList(),
    @SerialName("next_url") val nextUrl: String? = null,
)

@Serializable
internal class SeriesDetail(
    val id: Long,
    val title: String,
    val caption: String = "",
    @SerialName("cover_image_urls") val coverImages: SeriesCover? = null,
    @SerialName("series_work_count") val workCount: Int = 0,
    @SerialName("watchlist_added") val inWatchlist: Boolean = false,
    val user: PixivUser? = null,
) {
    fun toSManga(authorId: Long? = null): SManga = SManga.create().apply {
        url = Target.Series(id, user?.id ?: authorId).value
        title = this@SeriesDetail.title
        author = user?.name
        artist = user?.name
        thumbnail_url = coverImages?.medium
        description = buildString {
            val text = Jsoup.parseBodyFragment(caption).text()
            if (text.isNotEmpty()) append(text).append("\n\n")
            append("Works: ").append(workCount)
            if (inWatchlist) append(" | In watchlist")
        }
    }
}

@Serializable
internal class SeriesCover(
    val medium: String? = null,
)

@Serializable
internal class BookmarkTagsResponse(
    @SerialName("bookmark_tags") val tags: List<BookmarkTag> = emptyList(),
    @SerialName("next_url") val nextUrl: String? = null,
)

@Serializable
internal class BookmarkTag(
    val name: String,
    val count: Int,
) {
    fun toSManga(restrict: String): SManga = SManga.create().apply {
        url = Target.BookmarkTag(restrict, name).value
        title = name.ifEmpty { "Untagged" }
        description = "Bookmarks: $count"
    }
}

@Serializable
internal class WatchlistResponse(
    val series: List<WatchlistSeries> = emptyList(),
    @SerialName("next_url") val nextUrl: String? = null,
)

@Serializable
internal class WatchlistSeries(
    val id: Long,
    val title: String,
    val user: PixivUser? = null,
    val url: String? = null,
    @SerialName("published_content_count") val workCount: Int = 0,
    @SerialName("last_published_content_datetime") val lastPublished: String? = null,
) {
    fun toSManga(): SManga = SManga.create().apply {
        url = Target.Series(id, user?.id).value
        title = this@WatchlistSeries.title
        author = user?.name
        artist = user?.name
        thumbnail_url = this@WatchlistSeries.url
        description = buildString {
            append("Works: ").append(workCount)
            lastPublished?.let { append(" | Last updated: ").append(it) }
        }
    }
}

@Serializable
internal class WebEnvelope<T>(
    val error: Boolean = false,
    val message: String = "",
    val body: T? = null,
)

@Serializable
internal class WebResults(
    val illusts: List<WebIllust> = emptyList(),
)

@Serializable
internal class WebIllustDetails(
    @SerialName("illust_details") val illust: WebIllust? = null,
)

@Serializable
internal class WebIllustsDetails(
    @SerialName("illust_details") val illusts: List<WebIllust> = emptyList(),
)

@Serializable
internal class WebRankings(
    val ranking: List<WebRanking> = emptyList(),
)

@Serializable
internal class WebRanking(
    val illustId: String,
)

@Serializable
internal class WebIllust(
    val id: String,
    val title: String,
    val type: String? = null,
    val url: String? = null,
    val comment: String? = null,
    val tags: List<String> = emptyList(),
    val series: WebSeries? = null,
    @SerialName("author_details") val author: WebAuthor? = null,
    @SerialName("upload_timestamp") val uploadTimestamp: Long? = null,
    @SerialName("is_ad_container") val isAd: Int = 0,
) {
    fun toSManga(groupSeries: Boolean): SManga = SManga.create().apply {
        val webSeries = this@WebIllust.series
        if (groupSeries && webSeries != null) {
            url = Target.Series(
                webSeries.id.toLong(),
                webSeries.userId?.toLongOrNull() ?: this@WebIllust.author?.userId?.toLongOrNull(),
            ).value
            title = webSeries.title
        } else {
            url = Target.Artwork(id.toLong()).value
            title = this@WebIllust.title
        }
        thumbnail_url = this@WebIllust.url
        author = this@WebIllust.author?.userName
        artist = this@WebIllust.author?.userName
    }
}

@Serializable
internal class WebSeries(
    val id: String,
    val title: String,
    val userId: String? = null,
)

@Serializable
internal class WebAuthor(
    @SerialName("user_id") val userId: String? = null,
    @SerialName("user_name") val userName: String? = null,
)

@Serializable
internal class WebPage(
    val urls: WebPageUrls,
)

@Serializable
internal class WebPageUrls(
    @SerialName("thumb_mini") val thumbMini: String? = null,
    val small: String? = null,
    val regular: String? = null,
    val original: String? = null,
)

@Serializable
internal class WebSeriesDetails(
    val series: WebSeriesInfo,
)

@Serializable
internal class WebSeriesInfo(
    val id: String,
    val title: String,
    val caption: String? = null,
    val userId: String? = null,
)

@Serializable
internal class WebSeriesContents(
    @SerialName("series_contents") val illusts: List<WebIllust> = emptyList(),
)

@Serializable
internal class WebUserInfo(
    val userId: String,
    val name: String,
    val image: String? = null,
    val imageBig: String? = null,
    val comment: String? = null,
)
