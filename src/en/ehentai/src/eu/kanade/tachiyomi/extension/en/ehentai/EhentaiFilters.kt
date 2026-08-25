package eu.kanade.tachiyomi.extension.en.ehentai

import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_ALL
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_ARTIST_CG
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_ASIAN_PORN
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_COSPLAY
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_DOUJINSHI
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_GAME_CG
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_IMAGE_SET
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_MANGA
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_MISC
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_NON_H
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.CAT_WESTERN
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Category filter. Options mirror the site's category panel; each entry maps
 * to one bit of the `f_cats` exclusion mask (see [buildSearchParams]).
 */
class CategoryFilter :
    Filter.Select<String>(
        "分类 (Category)",
        arrayOf("全部", "Doujinshi", "Manga", "Artist CG", "Game CG", "Western", "Non-H", "Image Set", "Cosplay", "Asian Porn", "Misc"),
    )

/** Minimum rating filter -> `f_srdd` (0 = any). */
class RatingFilter :
    Filter.Select<String>(
        "最低评分 (Minimum rating)",
        arrayOf("不限", "2 星", "3 星", "4 星", "5 星"),
    )

/** Language filter -> appended to `f_search` as a `language:xxx` keyword. */
class LanguageFilter :
    Filter.Select<String>(
        "语言 (Language)",
        arrayOf("不限", "English", "中文", "日本語", "한국어", "Français", "Deutsch", "Español", "Other"),
    )

/** Page count range (from) -> `f_spf`. */
class PagesFromFilter : Filter.Text("页数下限 (Pages from)")

/** Page count range (to) -> `f_spt`. */
class PagesToFilter : Filter.Text("页数上限 (Pages to)")

/** Include expunged galleries -> `f_sh=on`. */
class ExpungedFilter : Filter.CheckBox("包含已删除画廊 (Browse expunged)")

/** Require a gallery torrent -> `f_sto=on`. */
class TorrentFilter : Filter.CheckBox("要求有种子 (Require torrent)")

/** Switches the list endpoint between the public index and the logged-in favorites page. */
class BrowseModeFilter :
    Filter.Select<String>(
        "浏览来源 (Browse source)",
        arrayOf("普通搜索", "我的收藏夹", "我的关注标签"),
    )

/** E-Hentai has ten favorite categories, indexed from 0 to 9. */
class FavoriteCategoryFilter(
    labels: Array<String> = Array(10) { index -> "收藏夹 ${index + 1}" },
) : Filter.Select<String>(
    "收藏夹分类 (Favorite category)",
    labels,
)

/** Optional E-Hentai query evaluated inside the selected favorite category. */
class FavoriteKeywordFilter : Filter.Text("收藏夹内标签/关键词 (Favorite tag / keyword)")

/**
 * Category bits in the same order as [CategoryFilter] options (index 0 = All).
 * The values are the site's category-panel ids: `f_cats` is an EXCLUSION mask,
 * so a selected category is sent as `CAT_ALL xor bit`.
 */
private val CATEGORY_BITS = intArrayOf(
    0, // 全部
    CAT_DOUJINSHI,
    CAT_MANGA,
    CAT_ARTIST_CG,
    CAT_GAME_CG,
    CAT_WESTERN,
    CAT_NON_H,
    CAT_IMAGE_SET,
    CAT_COSPLAY,
    CAT_ASIAN_PORN,
    CAT_MISC,
)

/** `f_srdd` values in the same order as [RatingFilter] options (index 0 = Any). */
private val RATING_VALUES = intArrayOf(0, 2, 3, 4, 5)

/** `language:xxx` keywords in the same order as [LanguageFilter] options (index 0 = Any). */
private val LANGUAGE_KEYWORDS = arrayOf(
    null,
    "language:english",
    "language:chinese",
    "language:japanese",
    "language:korean",
    "language:french",
    "language:german",
    "language:spanish",
    "language:other",
)

fun ehentaiFilterList(favoriteCategoryNames: Array<String> = Array(10) { index -> "收藏夹 ${index + 1}" }): FilterList = FilterList(
    Filter.Header("浏览 (Browse)"),
    BrowseModeFilter(),
    FavoriteCategoryFilter(favoriteCategoryNames),
    FavoriteKeywordFilter(),
    Filter.Header("分类 (Category)"),
    CategoryFilter(),
    Filter.Header("搜索选项 (Search options)"),
    RatingFilter(),
    LanguageFilter(),
    Filter.Header("页数范围 (Page count)"),
    PagesFromFilter(),
    PagesToFilter(),
    Filter.Header("高级 (Advanced)"),
    ExpungedFilter(),
    TorrentFilter(),
)

/**
 * Pure function mapping the search query + filter states onto the site's
 * search URL. Kept free of any source state so it can be unit-tested.
 *
 * Verified parameter semantics (2026-08-15):
 * - `f_cats` is an exclusion mask; a single category is sent as `1023 xor bit`.
 * - `f_spf`/`f_spt` take plain numbers; empty values are omitted.
 * - `f_srdd` takes 2..5 (0 = omit).
 * - `f_sh`/`f_sto` are `on` checkboxes.
 * - With no [watchedTerms], watched mode uses the site's authoritative `/watched` feed.
 * - [watchedTerms] is only used for optional local watched tags, which are merged separately.
 * - The old `f_sname`/`f_stags`/`f_sdesc`/`f_sr`/`f_sfl`/`f_sdd`/`f_si` params are gone.
 */
fun buildSearchParams(
    baseUrl: String,
    query: String,
    filters: FilterList,
    watchedTerms: String? = null,
): okhttp3.HttpUrl.Builder {
    val browseMode = filters.filterIsInstance<BrowseModeFilter>().firstOrNull()?.state ?: 0
    val favoriteCategory = filters.filterIsInstance<FavoriteCategoryFilter>().firstOrNull()?.state ?: 0
    val favoriteKeyword = filters.filterIsInstance<FavoriteKeywordFilter>().firstOrNull()?.state?.trim().orEmpty()
    val builder = baseUrl.toHttpUrl().newBuilder()
    if (browseMode == 1) {
        builder.addPathSegment("favorites.php")
        builder.addQueryParameter("favcat", favoriteCategory.toString())
    } else if (browseMode == 2 && watchedTerms == null) {
        builder.addPathSegment("watched")
    }
    var search = query.trim()

    filters.forEach { filter ->
        when (filter) {
            is BrowseModeFilter, is FavoriteCategoryFilter -> Unit
            is FavoriteKeywordFilter -> {
                if (browseMode == 1 && favoriteKeyword.isNotBlank()) {
                    search = listOf(search, favoriteKeyword).filter { it.isNotBlank() }.joinToString(" ")
                }
            }
            is CategoryFilter -> {
                val bit = CATEGORY_BITS.getOrElse(filter.state) { 0 }
                if (bit != 0) {
                    builder.addQueryParameter("f_cats", (CAT_ALL xor bit).toString())
                }
            }
            is RatingFilter -> {
                val rating = RATING_VALUES.getOrElse(filter.state) { 0 }
                if (rating > 0) {
                    builder.addQueryParameter("f_srdd", rating.toString())
                }
            }
            is LanguageFilter -> {
                val keyword = LANGUAGE_KEYWORDS.getOrElse(filter.state) { null }
                if (keyword != null) {
                    search = listOf(search, keyword).filter { it.isNotBlank() }.joinToString(" ")
                }
            }
            is PagesFromFilter -> {
                val from = filter.state.trim()
                if (from.isNotBlank()) {
                    builder.addQueryParameter("f_spf", from)
                }
            }
            is PagesToFilter -> {
                val to = filter.state.trim()
                if (to.isNotBlank()) {
                    builder.addQueryParameter("f_spt", to)
                }
            }
            is ExpungedFilter -> {
                if (filter.state) {
                    builder.addQueryParameter("f_sh", "on")
                }
            }
            is TorrentFilter -> {
                if (filter.state) {
                    builder.addQueryParameter("f_sto", "on")
                }
            }
            else -> {}
        }
    }

    if (browseMode == 2 && watchedTerms != null) {
        search = listOf(search, watchedTerms)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
    if (search.isNotBlank()) {
        builder.addQueryParameter("f_search", search)
    }
    return builder
}

/** True when no filter would change the request compared to a plain search. */
fun FilterList.hasNoActiveFilters(): Boolean = all { filter ->
    val browseMode = filterIsInstance<BrowseModeFilter>().firstOrNull()?.state ?: 0
    when (filter) {
        is BrowseModeFilter -> filter.state == 0
        is FavoriteCategoryFilter -> browseMode != 1 || filter.state == 0
        is FavoriteKeywordFilter -> browseMode != 1 || filter.state.isBlank()
        is CategoryFilter -> filter.state == 0
        is RatingFilter -> filter.state == 0
        is LanguageFilter -> filter.state == 0
        is PagesFromFilter -> filter.state.isBlank()
        is PagesToFilter -> filter.state.isBlank()
        is ExpungedFilter -> !filter.state
        is TorrentFilter -> !filter.state
        else -> true
    }
}
