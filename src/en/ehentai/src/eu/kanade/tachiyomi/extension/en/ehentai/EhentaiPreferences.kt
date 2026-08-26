package eu.kanade.tachiyomi.extension.en.ehentai

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.DEFAULT_USER_AGENT
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.DOMAIN_EHENTAI
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.DOMAIN_EXHENTAI
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_COOKIE
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_FAVORITE_NAMES
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_IGNEOUS
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_IMAGE_QUALITY
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_MEMBER_ID
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_PASS_HASH
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_PRE_RESOLVE_IMAGES
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_PROXY_URL
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_REQUEST_INTERVAL
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_USER_AGENT
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_WATCHED_EXCLUDE_TAGS
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.PREF_WATCHED_INCLUDE_TAGS
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.QUALITY_ORIGINAL
import eu.kanade.tachiyomi.extension.en.ehentai.Constants.QUALITY_STANDARD

/**
 * Thin wrapper over the source-scoped [SharedPreferences].
 *
 * Values are read on every access (no caching) so that preference changes
 * take effect without restarting the app.
 */
class EhentaiPreferences(
    private val preferences: SharedPreferences,
) {

    init {
        migrateCombinedCookie()
    }

    /**
     * Login cookie string in a form accepted by OkHttp.
     *
     * Users commonly paste the value together with a trailing full-width
     * punctuation mark or a `Cookie:` prefix. OkHttp rejects non-ASCII header
     * characters, so parse only the three login fields and discard malformed
     * values before the network layer injects them into requests.
     */
    val loginCookies: List<Pair<String, String>>
        get() = listOf(
            PREF_MEMBER_ID,
            PREF_PASS_HASH,
            PREF_IGNEOUS,
        ).mapNotNull { name ->
            cookieValue(name)?.let { name to it }
        }

    val cookie: String
        get() = loginCookies.joinToString("; ") { (name, value) -> "$name=$value" }

    val hasLoginCookie: Boolean
        get() = listOf(PREF_MEMBER_ID, PREF_PASS_HASH, PREF_IGNEOUS).all { cookieValue(it) != null }

    val userAgent: String
        get() = preferences.getString(PREF_USER_AGENT, DEFAULT_USER_AGENT)
            ?.trim()
            ?.ifBlank { DEFAULT_USER_AGENT }
            ?: DEFAULT_USER_AGENT

    /** Whether the user asked for original images (requires a valid login cookie). */
    val wantOriginal: Boolean
        get() = preferences.getString(PREF_IMAGE_QUALITY, QUALITY_STANDARD) == QUALITY_ORIGINAL

    val preResolveImages: Boolean
        get() = preferences.getBoolean(PREF_PRE_RESOLVE_IMAGES, false)

    /** Delay between page-type requests (list / gallery / viewer), 0 = disabled. */
    val requestIntervalMs: Long
        get() = preferences.getString(PREF_REQUEST_INTERVAL, "0")?.toLongOrNull() ?: 0L

    val proxyUrl: String
        get() = preferences.getString(PREF_PROXY_URL, "").orEmpty().trim()

    /** Optional local terms merged with the account's server-generated watched feed. */
    val watchedIncludeTags: List<String>
        get() = parseTagList(preferences.getString(PREF_WATCHED_INCLUDE_TAGS, "").orEmpty())

    val watchedExcludeTags: List<String>
        get() = parseTagList(preferences.getString(PREF_WATCHED_EXCLUDE_TAGS, "").orEmpty())

    /** Favorite category labels are learned from favorites.php after login. */
    val favoriteCategoryNames: Array<String>
        get() {
            val saved = preferences.getString(PREF_FAVORITE_NAMES, "")
                ?.split('|')
                .orEmpty()
            return Array(10) { index ->
                saved.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
                    ?: "收藏夹 ${index + 1}"
            }
        }

    fun saveFavoriteCategoryNames(names: List<String>) {
        val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.take(10)
        if (cleaned.size == 10) {
            preferences.edit().putString(PREF_FAVORITE_NAMES, cleaned.joinToString("|")).apply()
        }
    }

    /**
     * True when the host (or any subdomain) belongs to the e-hentai family,
     * i.e. the only place the login cookie may be sent.
     */
    fun isSiteHost(host: String): Boolean = host == DOMAIN_EHENTAI || host.endsWith(".$DOMAIN_EHENTAI") ||
        host == DOMAIN_EXHENTAI || host.endsWith(".$DOMAIN_EXHENTAI")

    fun setupPreferenceScreen(screen: PreferenceScreen) {
        val context = screen.context

        EditTextPreference(context).apply {
            key = PREF_MEMBER_ID
            title = "ipb_member_id"
            summary = "可填写值本身，也可直接填写 ipb_member_id=...；三个值必须来自同一浏览器和网络出口。"
            dialogTitle = "ipb_member_id"
            setDefaultValue(legacyCookieValue(PREF_MEMBER_ID).orEmpty())
        }.let { screen.addPreference(it) }

        EditTextPreference(context).apply {
            key = PREF_PASS_HASH
            title = "ipb_pass_hash"
            summary = "可填写值本身，也可直接填写 ipb_pass_hash=...；三个值必须来自同一浏览器和网络出口。"
            dialogTitle = "ipb_pass_hash"
            setDefaultValue(legacyCookieValue(PREF_PASS_HASH).orEmpty())
        }.let { screen.addPreference(it) }

        EditTextPreference(context).apply {
            key = PREF_IGNEOUS
            title = "igneous"
            summary = "可填写值本身，也可直接填写 igneous=...；三个值必须来自同一浏览器和网络出口。"
            dialogTitle = "igneous"
            setDefaultValue(legacyCookieValue(PREF_IGNEOUS).orEmpty())
        }.let { screen.addPreference(it) }

        EditTextPreference(context).apply {
            key = PREF_WATCHED_INCLUDE_TAGS
            title = "本地补充关注标签 (Extra watched tags)"
            summary = "可选。账号 My Tags 会自动读取；这里只补充额外标签，多个标签用逗号或换行分隔。"
            dialogTitle = "本地补充关注标签"
            setDefaultValue("")
        }.let { screen.addPreference(it) }

        EditTextPreference(context).apply {
            key = PREF_WATCHED_EXCLUDE_TAGS
            title = "本地补充排除标签 (Extra hidden tags)"
            summary = "可选。账号 My Tags 中的 Hidden 标签已自动过滤；这里只补充额外排除标签，最多 10 个。"
            dialogTitle = "本地补充排除标签"
            setDefaultValue("")
        }.let { screen.addPreference(it) }

        EditTextPreference(context).apply {
            key = PREF_PROXY_URL
            title = "代理地址 (Proxy URL)"
            summary = "可选。留空则跟随应用代理；Suwayomi + Clash 可填写 http://127.0.0.1:7890。支持 http://、https://、socks:// 和 socks5://。修改后需重启 Suwayomi。"
            dialogTitle = "代理地址"
            setDefaultValue("")
        }.let { screen.addPreference(it) }

        EditTextPreference(context).apply {
            key = PREF_USER_AGENT
            title = "User-Agent"
            summary = "默认使用浏览器 UA；若被 Cloudflare 拦截（403/503）可尝试更换"
            dialogTitle = "User-Agent"
            setDefaultValue(DEFAULT_USER_AGENT)
        }.let { screen.addPreference(it) }

        ListPreference(context).apply {
            key = PREF_IMAGE_QUALITY
            title = "图片质量 (Image quality)"
            summary = "原图需要有效登录 Cookie；获取失败时自动回退标准图"
            entries = arrayOf("标准图（默认）", "原图")
            entryValues = arrayOf(QUALITY_STANDARD, QUALITY_ORIGINAL)
            setDefaultValue(QUALITY_STANDARD)
        }.let { screen.addPreference(it) }

        SwitchPreferenceCompat(context).apply {
            key = PREF_PRE_RESOLVE_IMAGES
            title = "预解析图片地址"
            summary = "进入阅读前就解析全部图片地址；大画廊会明显变慢，默认关闭"
            setDefaultValue(false)
        }.let { screen.addPreference(it) }

        ListPreference(context).apply {
            key = PREF_REQUEST_INTERVAL
            title = "请求间隔 (Request interval)"
            summary = "页面类请求（列表/详情/查看页）之间的等待时间，用于避免 429；图片下载不受影响"
            entries = arrayOf("无（默认）", "0.5 秒", "1 秒", "2 秒")
            entryValues = arrayOf("0", "500", "1000", "2000")
            setDefaultValue("0")
        }.let { screen.addPreference(it) }
    }

    private fun migrateCombinedCookie() {
        if (listOf(PREF_MEMBER_ID, PREF_PASS_HASH, PREF_IGNEOUS).any { preferences.contains(it) }) return
        val values = parseCombinedCookie(preferences.getString(PREF_COOKIE, "").orEmpty())
        if (values.isEmpty()) return
        preferences.edit().apply {
            values[PREF_MEMBER_ID]?.let { putString(PREF_MEMBER_ID, it) }
            values[PREF_PASS_HASH]?.let { putString(PREF_PASS_HASH, it) }
            values[PREF_IGNEOUS]?.let { putString(PREF_IGNEOUS, it) }
            apply()
        }
    }

    private fun legacyCookieValue(name: String): String? = parseCombinedCookie(preferences.getString(PREF_COOKIE, "").orEmpty())[name]

    private fun cookieValue(name: String): String? = normalizeFieldCookieValue(name, preferences.getString(name, "").orEmpty())
}

private fun parseCombinedCookie(raw: String): Map<String, String> = raw
    .removePrefix("Cookie:")
    .split(';')
    .mapNotNull { part ->
        val pieces = part.trim().split('=', limit = 2)
        if (pieces.size != 2) return@mapNotNull null

        val name = pieces[0].trim()
        if (name !in LOGIN_COOKIE_NAMES) return@mapNotNull null

        normalizeCookieValue(pieces[1])?.let { name to it }
    }
    .toMap()

private val LOGIN_COOKIE_NAMES = setOf(PREF_MEMBER_ID, PREF_PASS_HASH, PREF_IGNEOUS)

private fun normalizeCookieValue(raw: String): String? {
    val value = raw.trim().trim('"', '\'').trimEnd { it.isWhitespace() || it in TRAILING_COOKIE_PUNCTUATION }
    return value.takeIf { it.isNotEmpty() && it.all { char -> char.code in 0x21..0x7E } }
}

private fun normalizeFieldCookieValue(name: String, raw: String): String? {
    val text = raw.removePrefix("Cookie:").trim()
    val namedValue = text.split(';')
        .map { it.trim() }
        .firstOrNull { it.startsWith("$name=", ignoreCase = true) }
        ?.substringAfter('=')
    val value = namedValue ?: text
    return normalizeCookieValue(value.takeUnless { it.contains('=') } ?: value.substringAfter('='))
}

private fun parseTagList(raw: String): List<String> = raw
    .split(',', '\n', '\r')
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()

private val TRAILING_COOKIE_PUNCTUATION = setOf('.', ',', ':', ';', '!', '?', '\u3002', '\uFF0E', '\uFF0C', '\uFF1B')
