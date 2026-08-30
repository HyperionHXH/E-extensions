package eu.kanade.tachiyomi.extension.en.ehentai

import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_COVER
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_DESCRIPTION
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_META_ROWS
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_PAGE_COUNT_TEXT
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_PAGE_LINKS
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_TAG_NAMESPACE
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_TAG_ROWS
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_TITLE_EN
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_TITLE_JP
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_UPLOADER
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.GALLERY_VIEWER_LINKS
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.LIST_GALLERY_LINK_SELECTOR
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.LIST_TAGS_SELECTOR
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.VIEWER_IMAGE
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.VIEWER_ORIGINAL_LINK
import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

// ---------------------------------------------------------------------------
// List pages (search results / popular / front page share one structure)
// ---------------------------------------------------------------------------

/**
 * Parses every gallery row of a list page into an [SManga].
 * URLs are absolute; the caller converts them with `setUrlWithoutDomain`.
 *
 * The [factory] seam exists for unit tests (the compile-only extensions-lib
 * stubs `SManga.create()`); production uses the default.
 */
fun parseMangaList(doc: Document, factory: () -> SManga = { SManga.create() }): List<SManga> = doc
    .select(LIST_GALLERY_LINK_SELECTOR)
    .filter { it.selectFirst(".glink") != null }
    .mapNotNull { link ->
        val row = link.parents().firstOrNull { parent ->
            parent.tagName() == "tr" && parent.selectFirst(".glink") != null
        } ?: link.parents().firstOrNull { parent ->
            parent.selectFirst(".glink") != null && parent.selectFirst("img") != null
        } ?: link
        parseMangaRow(link, row, factory())
    }

private fun parseMangaRow(link: Element, row: Element, manga: SManga): SManga? {
    val title = link.selectFirst(".glink")?.text() ?: return null

    return manga.apply {
        url = link.absUrl("href")
        this.title = title
        thumbnail_url = parseCoverUrl(row)
        genre = parseListTags(row)
    }
}

/**
 * Cover of a list row: the `data-src` (lazy-loaded) or `src` attribute of the
 * cover image. Ignores placeholder GIFs and the small torrent/arrow icons.
 */
private fun parseCoverUrl(row: Element): String? = row.select("img").firstOrNull { img ->
    val src = img.attr("data-src").ifBlank { img.attr("src") }
    !src.startsWith("data:") &&
        !src.contains("/g/t.png") &&
        !src.contains("/g/td.png")
}?.let { img -> img.attr("data-src").ifBlank { img.attr("src") } }

/** Inline tags of a list row: `div.gt` elements whose `title` is `namespace:tag`. */
private fun parseListTags(row: Element): String? {
    val tags = row.select(LIST_TAGS_SELECTOR).mapNotNull { it.attr("title").takeIf { t -> t.isNotBlank() } }
    return tags.joinToString(", ").ifEmpty { null }
}

/** Reads the user's ten favorite category labels from the favorites page. */
fun parseFavoriteCategoryNames(doc: Document): List<String> {
    val blocks = doc.select(".nosel > .fp").ifEmpty { doc.select(".fp") }
    return blocks.mapNotNull { block ->
        block.select("div").lastOrNull()?.text()?.trim()?.takeIf { it.isNotEmpty() }
    }.take(10)
}

class AccountTag(
    val name: String,
    val watched: Boolean,
    val hidden: Boolean,
    val weight: Int,
)

class AccountTagSet(
    val numbers: List<Int>,
    val enabled: Boolean,
    val tags: List<AccountTag>,
)

/** Parses one E-Hentai My Tags set. The same account settings apply to ExHentai. */
fun parseAccountTagSet(doc: Document): AccountTagSet {
    val numbers = doc.select("#tagset_outer select option[value]")
        .mapNotNull { it.attr("value").toIntOrNull() }
        .filter { it > 0 }
        .distinct()

    val tags = doc.select("#usertags_outer > div[id^=usertag_]").mapNotNull { block ->
        val tagId = block.id().removePrefix("usertag_").toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null
        val name = block.selectFirst("#tagpreview_$tagId")?.attr("title")?.trim().orEmpty()
        if (name.isEmpty()) return@mapNotNull null

        AccountTag(
            name = name,
            watched = block.selectFirst("#tagwatch_$tagId[checked]") != null,
            hidden = block.selectFirst("#taghide_$tagId[checked]") != null,
            weight = block.selectFirst("#tagweight_$tagId")?.attr("value")?.toIntOrNull() ?: 0,
        )
    }

    return AccountTagSet(
        numbers = numbers,
        enabled = doc.selectFirst("#tagset_enable[checked]") != null,
        tags = tags,
    )
}

fun exactTagTerm(raw: String): String? {
    val value = canonicalTag(raw) ?: return null
    val namespace = value.substringBefore(':')
    val tag = value.substringAfter(':')
    return "$namespace:\"${tag.replace("\"", "\\\"")}$\""
}

fun canonicalTag(raw: String): String? {
    val value = raw.trim().removePrefix("~").removePrefix("-").trim()
    if (value.isEmpty()) return null

    val namespace = value.substringBefore(':', "tag").trim().lowercase()
    val tag = value.substringAfter(':', value)
        .trim()
        .removeSurrounding("\"")
        .removeSuffix("$")
        .trim()
        .lowercase()
    if (tag.isEmpty()) return null

    return "$namespace:$tag"
}

// ---------------------------------------------------------------------------
// Pagination (cursor based: `var nexturl="..."` / `var prevurl="..."`)
// ---------------------------------------------------------------------------

private val NEXT_URL_REGEX = Regex("""var\s+nexturl\s*=\s*"([^"]*)"""")
private val NEXT_LINK_REGEX = Regex("""<a[^>]*id=[\"']dnext[\"'][^>]*href=[\"']([^\"']+)""", RegexOption.IGNORE_CASE)

/** The absolute URL of the next results page, or null on the last page. */
fun parseNextUrl(html: String): String? = NEXT_URL_REGEX.find(html)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() }
    ?: NEXT_LINK_REGEX.find(html)?.groupValues?.get(1)?.replace("&amp;", "&")?.takeIf { it.isNotEmpty() }

fun hasNextPage(html: String): Boolean = !parseNextUrl(html).isNullOrEmpty()

// ---------------------------------------------------------------------------
// Gallery detail page
// ---------------------------------------------------------------------------

/** Fills [manga] with the gallery page's metadata (title, cover, uploader, tags, …). */
fun parseGalleryDetails(doc: Document, manga: SManga): SManga {
    val titleEn = doc.selectFirst(GALLERY_TITLE_EN)?.text()
    val titleJp = doc.selectFirst(GALLERY_TITLE_JP)?.text()
    manga.title = titleEn ?: titleJp ?: runCatching { manga.title }.getOrNull() ?: "Untitled Gallery"
    manga.thumbnail_url = parseGalleryCover(doc) ?: manga.thumbnail_url
    manga.author = parseUploader(doc)
    manga.genre = parseTags(doc)
    manga.description = parseDescription(doc)
    manga.status = SManga.UNKNOWN
    manga.initialized = true
    return manga
}

/**
 * Cover of the gallery page. The new layout uses a CSS background image
 * (`#gd1 div[style*="url(...)"]`) instead of an `<img>`.
 */
fun parseGalleryCover(doc: Document): String? {
    doc.selectFirst("$GALLERY_COVER img[src]")?.absUrl("src")?.takeIf { it.isNotBlank() }?.let { return it }
    val style = doc.selectFirst("$GALLERY_COVER div[style*=url]")?.attr("style") ?: return null
    return COVER_URL_REGEX.find(style)?.groupValues?.get(1)
}

private val COVER_URL_REGEX = Regex("""url\(['\"]?(https?://[^)'\"]+)['\"]?\)""")

/** Metadata table value, e.g. parseMeta(doc, "Posted") or parseMeta(doc, "Length"). */
fun parseMeta(doc: Document, name: String): String? = doc.select(GALLERY_META_ROWS).firstOrNull { row ->
    row.selectFirst("td.gdt1")?.text()?.trimEnd(':')?.equals(name, ignoreCase = true) == true
}?.selectFirst("td.gdt2")?.text()?.trim()

fun parseUploader(doc: Document): String? = doc.selectFirst(GALLERY_UPLOADER)?.text()

/** All tags as `namespace:tag` joined with ", " (without the namespace column headers). */
fun parseTags(doc: Document): String? {
    val parts = doc.select(GALLERY_TAG_ROWS).flatMap { row ->
        val namespace = row.selectFirst(GALLERY_TAG_NAMESPACE)?.text()?.trimEnd(':')
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return@flatMap emptyList()
        row.select("td").getOrNull(1)?.select("a")
            .orEmpty()
            .mapNotNull { anchor ->
                anchor.text().trim().takeIf { it.isNotEmpty() }?.let { "$namespace:$it" }
            }
    }.distinct()
    return parts.joinToString(", ").ifEmpty { null }
}

/**
 * `#gd2` contains the `#gn`/`#gj` headings and optionally a free-text
 * description; strip the headings to get the description itself.
 */
fun parseDescription(doc: Document): String? {
    val container = doc.selectFirst(GALLERY_DESCRIPTION) ?: return null
    var text = container.text().trim()
    doc.selectFirst(GALLERY_TITLE_EN)?.text()?.let { text = text.trim().removePrefix(it).trim() }
    doc.selectFirst(GALLERY_TITLE_JP)?.text()?.let { text = text.trim().removePrefix(it).trim() }
    return text.ifEmpty { null }
}

/** Posted date of the gallery, epoch millis; 0 when unparseable. */
fun parsePostedDate(doc: Document): Long = parseDateToEpoch(parseMeta(doc, "Posted"))

/**
 * Total page count of the gallery. Uses the "Length: N pages" metadata row,
 * falling back to the "Showing x - y of z images" footer.
 * Returns -1 when unknown.
 */
fun parsePageCount(doc: Document): Int {
    parseMeta(doc, "Length")?.let { text ->
        PAGE_COUNT_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    }
    doc.selectFirst(GALLERY_PAGE_COUNT_TEXT)?.text()?.let { text ->
        SHOWING_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    }
    return -1
}

private val PAGE_COUNT_REGEX = Regex("""(\d+)\s+pages""")
private val SHOWING_REGEX = Regex("""of\s+(\d+)\s+images""")

/** Number of thumbnail pages exposed by the gallery navigation (`?p=N`). */
fun parseThumbnailPageCount(doc: Document): Int = doc.select(GALLERY_PAGE_LINKS)
    .mapNotNull { link ->
        Regex("[?&]p=(\\d+)").find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
    }
    .maxOrNull()
    ?.plus(1)
    ?: 1

/** Viewer page URLs of one thumbnail page (`#gdt a[href*=/s/]`), absolute. */
fun parseViewerLinks(doc: Document): List<String> = doc.select(GALLERY_VIEWER_LINKS)
    .mapNotNull { it.absUrl("href").takeIf(String::isNotBlank) }

// ---------------------------------------------------------------------------
// Viewer page (single image)
// ---------------------------------------------------------------------------

/**
 * Image URL of a viewer page. With [wantOriginal] and a present `/fullimg/`
 * link the original file URL is returned; otherwise the standard `img#img`
 * src. Throws when no image could be found (callers surface the error in
 * the reader, which then retries/aborts that page normally).
 */
fun parseImageUrl(doc: Document, wantOriginal: Boolean): String {
    if (wantOriginal) {
        doc.selectFirst(VIEWER_ORIGINAL_LINK)?.absUrl("href")
            ?.takeIf(String::isNotBlank)
            ?.let { return it }
    }
    return doc.selectFirst(VIEWER_IMAGE)?.absUrl("src")
        ?.takeIf(String::isNotBlank)
        ?: throw Exception("Could not find a non-empty image URL in the viewer page")
}

// ---------------------------------------------------------------------------
// Dates
// ---------------------------------------------------------------------------

private val DATE_FORMATS = arrayOf(
    "yyyy-MM-dd HH:mm", // current site format, e.g. "2026-08-15 05:47"
    "dd MMMM yyyy, HH:mm", // legacy format, e.g. "17 September 2024, 12:00"
)

/** Parses a site date string to epoch millis; 0L on failure. */
fun parseDateToEpoch(text: String?): Long {
    if (text.isNullOrBlank()) return 0L
    val value = text.trim()
    for (format in DATE_FORMATS) {
        try {
            return SimpleDateFormat(format, Locale.US).parse(value)?.time ?: 0L
        } catch (_: ParseException) {
            // try the next format
        }
    }
    return 0L
}
