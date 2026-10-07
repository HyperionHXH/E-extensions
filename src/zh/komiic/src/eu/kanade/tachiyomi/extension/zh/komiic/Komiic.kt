package eu.kanade.tachiyomi.extension.zh.komiic

import android.os.Handler
import android.os.Looper
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.post
import keiyoushi.source.KeiSource
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.parseGraphQLAs
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import java.io.IOException
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

@Source
abstract class Komiic :
    KeiSource(),
    ConfigurableSource {

    override fun OkHttpClient.Builder.configureClient() = apply {
        callTimeout(60, TimeUnit.SECONDS)
        addInterceptor { chain ->
            val origin = chain.request()
            val host = baseUrl.removePrefix("https://")
            val request = origin.takeUnless { host != "komiic.com" && it.url.host.endsWith("komiic.com") } ?: origin.run {
                val newHost = url.host.removeSuffix("komiic.com") + host
                newBuilder().url(url.newBuilder().host(newHost).build()).build()
            }
            chain.proceed(request)
        }
        addInterceptor { chain ->
            val origin = chain.request()
            if (origin.url.toString().contains("api/image")) {
                ensureLogin()
                var response = try {
                    chain.proceed(origin)
                } catch (error: IOException) {
                    setStatus(KEY_IMAGE_STATUS, "图片下载失败：网络连接异常或超时")
                    throw error
                }
                if (response.code == 401 && hasCredentials()) {
                    response.close()
                    ensureLogin(force = true)
                    response = chain.proceed(origin)
                }
                if (response.code in listOf(401, 402, 403, 429)) {
                    val reason = when (response.code) {
                        401 -> "登录已失效，请验证登录状态"
                        402 -> "今日图片读取额度已用尽，请等待网站额度重置"
                        403 -> "网站拒绝图片请求（HTTP 403）"
                        else -> "网站限流（HTTP 429），请稍后重试"
                    }
                    if (response.code == 401) setStatus(KEY_LOGIN_STATUS, reason)
                    setStatus(KEY_IMAGE_STATUS, reason)
                    response.close()
                    throw IOException(reason)
                }
                if (!response.isSuccessful) setStatus(KEY_IMAGE_STATUS, "图片下载失败（HTTP ${response.code}）")
                response
            } else {
                chain.proceed(origin)
            }
        }
    }

    private val loginLock = Any()
    private val loginTestRunning = AtomicBoolean(false)
    private val statusPreferences = mutableMapOf<String, WeakReference<EditTextPreference>>()
    private val account by lazy { Account(client, { baseUrl }, { headers }) }
    private var sessionVerified = false
    private val pref by getPreferencesLazy()

    private fun hasCredentials() = !pref.getString(KEY_EMAIL, "").isNullOrBlank() && !pref.getString(KEY_PASSWORD, "").isNullOrEmpty()

    private fun ensureLogin(force: Boolean = false) = synchronized(loginLock) {
        val email = pref.getString(KEY_EMAIL, "").orEmpty().trim()
        val password = pref.getString(KEY_PASSWORD, "").orEmpty()
        val configured = email.isNotEmpty() && password.isNotEmpty()
        try {
            if (configured && (force || pref.getBoolean(KEY_ACCOUNT_CHANGED, false) || !account.hasSession())) {
                account.login(email, password)
                pref.edit().putBoolean(KEY_ACCOUNT_CHANGED, false).apply()
                sessionVerified = true
            } else if (account.hasSession()) {
                if (account.needsRefresh()) {
                    try {
                        account.refresh()
                    } catch (error: AccountException) {
                        if (!configured) throw error
                        account.login(email, password)
                    }
                    sessionVerified = true
                } else if (!sessionVerified || force) {
                    account.verify()
                    sessionVerified = true
                }
            } else {
                sessionVerified = false
                setStatus(KEY_LOGIN_STATUS, if (email.isEmpty() && password.isEmpty()) "游客模式（未登录）" else "邮箱或密码未填写完整")
                if (email.isNotEmpty() || password.isNotEmpty()) throw AccountException("邮箱或密码未填写完整")
                return@synchronized
            }
            if (email != pref.getString(KEY_EMAIL, "").orEmpty().trim() || password != pref.getString(KEY_PASSWORD, "").orEmpty()) {
                pref.edit().putBoolean(KEY_ACCOUNT_CHANGED, true).apply()
                throw AccountException("账号已修改，请重新验证")
            }
            setStatus(KEY_LOGIN_STATUS, "登录成功（官网已确认）")
        } catch (error: Exception) {
            sessionVerified = false
            setStatus(KEY_LOGIN_STATUS, failureStatus(error))
            throw IOException(failureStatus(error))
        }
    }

    private fun failureStatus(error: Exception) = when (error) {
        is AccountException -> error.message.orEmpty()
        is IOException -> "验证失败：网络连接异常或超时"
        else -> "验证失败：网站返回了无法识别的数据"
    }

    private fun setStatus(key: String, status: String) {
        val timestamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val summary = "$status\n检查时间：$timestamp"
        pref.edit().putString(key, summary).apply()
        Handler(Looper.getMainLooper()).post {
            statusPreferences[key]?.get()?.summary = summary
        }
    }

    private fun testLogin() {
        if (!loginTestRunning.compareAndSet(false, true)) return
        setStatus(KEY_LOGIN_STATUS, "正在验证...")
        thread(name = "komiic-login-test") {
            try {
                ensureLogin(force = true)
                try {
                    setStatus(KEY_IMAGE_STATUS, account.imageLimit().summary())
                } catch (error: Exception) {
                    setStatus(KEY_IMAGE_STATUS, failureStatus(error))
                }
            } catch (_: IOException) {
                setStatus(KEY_IMAGE_STATUS, "登录验证未通过，额度尚未查询")
            } finally {
                loginTestRunning.set(false)
            }
        }
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        listOf(KEY_LOGIN_STATUS to "登录状态", KEY_IMAGE_STATUS to "图片额度 / 下载状态").forEach { (statusKey, statusTitle) ->
            EditTextPreference(screen.context).apply {
                key = statusKey
                title = statusTitle
                summary = pref.getString(statusKey, "尚未验证")
                setEnabled(false)
                statusPreferences[statusKey] = WeakReference(this)
            }.also(screen::addPreference)
        }
        ListPreference(screen.context).apply {
            key = KEY_LOGIN_ACTION
            title = "验证登录状态"
            entries = arrayOf("验证登录并查询图片额度")
            entryValues = arrayOf("verify")
            setOnPreferenceChangeListener { _, _ ->
                testLogin()
                false
            }
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = KEY_EMAIL
            title = "登录邮箱"
            dialogTitle = "Komiic 登录邮箱（留空为游客）"
            setOnPreferenceChangeListener { _, value -> credentialsChanged(KEY_EMAIL, value.toString()) }
        }.also(screen::addPreference)
        EditTextPreference(screen.context).apply {
            key = KEY_PASSWORD
            title = "登录密码"
            dialogTitle = "Komiic 登录密码"
            summary = "登录后按网站账号的赞助额度读取图片；留空为游客额度"
            setOnBindEditTextListener { it.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD }
            setOnPreferenceChangeListener { _, value -> credentialsChanged(KEY_PASSWORD, value.toString()) }
        }.also(screen::addPreference)
        ListPreference(screen.context).apply {
            key = "CHAPTER_FILTER"
            title = "章節列表顯示"
            summary = "%s"
            entries = arrayOf("同時顯示卷和章節", "僅顯示章節", "僅顯示卷")
            entryValues = arrayOf("all", "chapter", "book")
            setDefaultValue("all")
        }.also(screen::addPreference)
    }

    private fun credentialsChanged(key: String, value: String): Boolean {
        pref.edit().putString(key, value).putBoolean(KEY_ACCOUNT_CHANGED, true).apply()
        setStatus(KEY_LOGIN_STATUS, "账号已修改，尚未验证")
        setStatus(KEY_IMAGE_STATUS, "尚未查询")
        if (hasCredentials()) testLogin()
        return true
    }

    private companion object {
        const val KEY_EMAIL = "KOMIIC_EMAIL"
        const val KEY_PASSWORD = "KOMIIC_PASS"
        const val KEY_LOGIN_STATUS = "KOMIIC_LOGIN_STATUS"
        const val KEY_IMAGE_STATUS = "KOMIIC_IMAGE_STATUS"
        const val KEY_LOGIN_ACTION = "KOMIIC_LOGIN_ACTION"
        const val KEY_ACCOUNT_CHANGED = "KOMIIC_ACCOUNT_CHANGED"
    }

    // Customize

    private val SManga.id get() = url.substringAfterLast("/")
    private val SChapter.id get() = url.substringAfterLast("/")

    private suspend fun OkHttpClient.query(body: RequestBody) = post("$baseUrl/api/query", body)

    private suspend fun mangasPage(page: Int, orderBy: OrderBy): MangasPage {
        val pagination = Pagination((page - 1) * PAGE_SIZE, orderBy)
        val response = client.query(commonQuery(ListingVariables(pagination)))
        return parseListing(response.parseGraphQLAs())
    }

    // Popular
    override suspend fun getPopularManga(page: Int) = mangasPage(page, OrderBy.MONTH_VIEWS)

    // Update
    override suspend fun getLatestUpdates(page: Int) = mangasPage(page, OrderBy.DATE_UPDATED)

    // Search
    override fun getFilterList(data: JsonElement?) = buildFilterList()

    override suspend fun getMangaByUrl(url: HttpUrl) = url.takeIf { url.pathSegments[0] == "comic" }?.let {
        val response = client.query(idsQuery(listOf(url.pathSegments[1])))
        parseListing(response.parseGraphQLAs()).mangas.first()
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val body = if (query.isNotBlank()) {
            searchQuery(query)
        } else {
            val variables = ListingVariables(Pagination((page - 1) * PAGE_SIZE))
            filters.filterIsInstance<KomiicFilter>().forEach { it.apply(variables) }
            listingQuery(variables)
        }
        val response = client.query(body)
        return parseListing(response.parseGraphQLAs())
    }

    // Manga & Chapter
    override fun getMangaUrl(manga: SManga) = baseUrl + manga.url

    override fun getChapterUrl(chapter: SChapter) = baseUrl + chapter.url + "/images/all"

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val response = client.query(mangaQuery(manga.id, fetchDetails, fetchChapters))
        val data = response.parseGraphQLAs<DataDto>()

        val sManga = if (fetchDetails) data.comicById!!.toSManga() else manga
        val sChapters = if (fetchChapters) {
            val rawChapters = data.chaptersByComicId!!.toMutableList()
            when (pref.getString("CHAPTER_FILTER", "all")) {
                "chapter" -> rawChapters.retainAll { it.type == "chapter" }
                "book" -> rawChapters.retainAll { it.type == "book" }
                else -> {}
            }
            rawChapters.sortWith(
                compareByDescending<ChapterDto> { it.type }.thenByDescending { it.serial.toFloatOrNull() },
            )
            rawChapters.map { it.toSChapter(manga.url) }
        } else {
            chapters
        }

        return SMangaUpdate(sManga, sChapters)
    }

    override val supportsRelatedMangas get() = true

    override suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> {
        val response = client.query(recommendQuery(manga.id))
        val comicIds = response.parseGraphQLAs<DataDto>().recommendComicById!!
        return parseListing(client.query(idsQuery(comicIds)).parseGraphQLAs()).mangas
    }

    // Page
    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val response = client.query(pageListQuery(chapter.id))
        val data = response.parseGraphQLAs<DataDto>()
        return data.imagesByChapterId!!.mapIndexed { index, image ->
            Page(index, baseUrl + "${chapter.url}/page/${index + 1}", "$baseUrl/api/image/${image.kid}")
        }
    }

    // Image
    override fun imageRequest(page: Page) = super.imageRequest(page).newBuilder()
        .addHeader("Accept", "*/*").header("Referer", page.url).build()
}
