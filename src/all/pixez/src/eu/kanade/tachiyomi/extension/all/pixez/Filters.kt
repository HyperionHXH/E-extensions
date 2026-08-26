package eu.kanade.tachiyomi.extension.all.pixez

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import keiyoushi.utils.firstInstance

internal enum class Feed(val title: String) {
    SEARCH("Search"),
    RECOMMENDED_ILLUST("Recommended illustrations"),
    RECOMMENDED_MANGA("Recommended manga"),
    FOLLOWING_PUBLIC("Following - public"),
    FOLLOWING_PRIVATE("Following - private"),
    BOOKMARKS_PUBLIC("Bookmarks - public"),
    BOOKMARKS_PRIVATE("Bookmarks - private"),
    BOOKMARK_TAGS_PUBLIC("Bookmark tags - public"),
    BOOKMARK_TAGS_PRIVATE("Bookmark tags - private"),
    RANKING("Ranking"),
    USER_WORKS("User works"),
    FOLLOWING_USERS("Following users"),
    RECOMMENDED_USERS("Recommended users"),
    WATCHLIST("Watched manga series"),
    RELATED("Related artworks"),
    SERIES("Series"),
}

internal enum class WorkType(val title: String, val value: String?) {
    ALL("All", null),
    ILLUST("Illustrations", "illust"),
    MANGA("Manga", "manga"),
}

internal enum class RankingMode(val title: String, val value: String) {
    DAILY("Daily", "day"),
    WEEKLY("Weekly", "week"),
    MONTHLY("Monthly", "month"),
    ROOKIE("Rookie", "week_rookie"),
    ORIGINAL("Original", "week_original"),
    MALE("Popular with men", "day_male"),
    FEMALE("Popular with women", "day_female"),
    AI("AI-generated", "day_ai"),
    R18_DAILY("R-18 daily", "day_r18"),
    R18_WEEKLY("R-18 weekly", "week_r18"),
    R18_MALE("R-18 popular with men", "day_male_r18"),
    R18_FEMALE("R-18 popular with women", "day_female_r18"),
    R18_AI("R-18 AI-generated", "day_r18_ai"),
}

internal enum class SearchTarget(val title: String, val value: String) {
    PARTIAL("Partial tag match", "partial_match_for_tags"),
    EXACT("Exact tag match", "exact_match_for_tags"),
    TITLE_CAPTION("Title and caption", "title_and_caption"),
}

internal enum class SortOrder(val title: String, val value: String) {
    NEWEST("Newest", "date_desc"),
    OLDEST("Oldest", "date_asc"),
    POPULAR("Popular (Premium)", "popular_desc"),
}

internal enum class AiMode(val title: String, val value: Int?) {
    ALL("All", null),
    HIDE("Hide AI-generated", 1),
    ONLY("AI-generated only", 2),
}

internal enum class AccountAction(val title: String) {
    NONE("None"),
    BOOKMARK_PUBLIC("Bookmark public"),
    BOOKMARK_PRIVATE("Bookmark private"),
    REMOVE_BOOKMARK("Remove bookmark"),
    FOLLOW_PUBLIC("Follow public"),
    FOLLOW_PRIVATE("Follow private"),
    UNFOLLOW("Unfollow"),
    WATCH_SERIES("Watch series"),
    UNWATCH_SERIES("Unwatch series"),
}

internal class FeedFilter : Filter.Select<String>("Browse", Feed.entries.map(Feed::title).toTypedArray())
internal class WorkTypeFilter : Filter.Select<String>("Type", WorkType.entries.map(WorkType::title).toTypedArray())
internal class RankingFilter : Filter.Select<String>("Ranking", RankingMode.entries.map(RankingMode::title).toTypedArray())
internal class SearchTargetFilter : Filter.Select<String>("Search target", SearchTarget.entries.map(SearchTarget::title).toTypedArray())
internal class SortFilter : Filter.Select<String>("Sort", SortOrder.entries.map(SortOrder::title).toTypedArray())
internal class AiFilter : Filter.Select<String>("AI works", AiMode.entries.map(AiMode::title).toTypedArray())
internal class UserIdFilter : Filter.Text("User ID")
internal class ArtworkIdFilter : Filter.Text("Artwork / series ID")
internal class BookmarkTagFilter : Filter.Text("Bookmark tag")
internal class StartDateFilter : Filter.Text("Start date (YYYY-MM-DD)")
internal class EndDateFilter : Filter.Text("End date (YYYY-MM-DD)")
internal class MinimumBookmarksFilter : Filter.Text("Minimum bookmarks")
internal class MaximumBookmarksFilter : Filter.Text("Maximum bookmarks")
internal class ActionFilter : Filter.Select<String>("Account action", AccountAction.entries.map(AccountAction::title).toTypedArray())
internal class ActionIdFilter : Filter.Text("Action target ID")
internal class ActionTagsFilter : Filter.Text("Bookmark tags (comma-separated)")

internal fun pixEzFilters() = FilterList(
    FeedFilter(),
    WorkTypeFilter(),
    RankingFilter(),
    SearchTargetFilter(),
    SortFilter(),
    AiFilter(),
    UserIdFilter(),
    ArtworkIdFilter(),
    BookmarkTagFilter(),
    StartDateFilter(),
    EndDateFilter(),
    MinimumBookmarksFilter(),
    MaximumBookmarksFilter(),
    Filter.Separator(),
    ActionFilter(),
    ActionIdFilter(),
    ActionTagsFilter(),
)

internal val FilterList.feed get() = Feed.entries[firstInstance<FeedFilter>().state]
internal val FilterList.workType get() = WorkType.entries[firstInstance<WorkTypeFilter>().state]
internal val FilterList.ranking get() = RankingMode.entries[firstInstance<RankingFilter>().state]
internal val FilterList.searchTarget get() = SearchTarget.entries[firstInstance<SearchTargetFilter>().state]
internal val FilterList.sort get() = SortOrder.entries[firstInstance<SortFilter>().state]
internal val FilterList.aiMode get() = AiMode.entries[firstInstance<AiFilter>().state]
internal val FilterList.userId get() = firstInstance<UserIdFilter>().state.trim()
internal val FilterList.artworkId get() = firstInstance<ArtworkIdFilter>().state.trim()
internal val FilterList.bookmarkTag get() = firstInstance<BookmarkTagFilter>().state.trim()
internal val FilterList.startDate get() = firstInstance<StartDateFilter>().state.trim()
internal val FilterList.endDate get() = firstInstance<EndDateFilter>().state.trim()
internal val FilterList.minimumBookmarks get() = firstInstance<MinimumBookmarksFilter>().state.trim()
internal val FilterList.maximumBookmarks get() = firstInstance<MaximumBookmarksFilter>().state.trim()
internal val FilterList.accountAction get() = AccountAction.entries[firstInstance<ActionFilter>().state]
internal val FilterList.actionId get() = firstInstance<ActionIdFilter>().state.trim()
internal val FilterList.actionTags get() = firstInstance<ActionTagsFilter>().state.trim()
