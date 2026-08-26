package eu.kanade.tachiyomi.extension.all.pixez

import android.content.SharedPreferences
import keiyoushi.network.get
import keiyoushi.network.post
import keiyoushi.utils.parseAs
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

internal class PixivApi(
    private val client: OkHttpClient,
    private val preferences: SharedPreferences,
    private val language: () -> String,
) {
    private val refreshMutex = Mutex()

    val isLoggedIn: Boolean
        get() = preferences.getString(PREF_REFRESH_TOKEN, "").orEmpty().isNotBlank()

    val userId: Long
        get() = preferences.getString(PREF_USER_ID, null)?.toLongOrNull()
            ?: throw IOException("Log in to PixEz in extension settings first")

    val userName: String?
        get() = preferences.getString(PREF_USER_NAME, null)

    suspend fun get(path: String, configure: HttpUrl.Builder.() -> Unit = {}): Response {
        val url = if (path.startsWith("https://")) {
            path.toHttpUrl().newBuilder()
        } else {
            APP_API_URL.toHttpUrl().newBuilder(path)!!
        }.apply(configure).build()

        var token = accessToken()
        var response = client.get(url, apiHeaders(token), ensureSuccess = false)
        if (response.code == 401) {
            response.close()
            token = refreshAccessToken(force = true)
            response = client.get(url, apiHeaders(token), ensureSuccess = false)
        }
        return response.requireSuccess()
    }

    suspend fun post(path: String, values: List<Pair<String, String>>): Response {
        val body = FormBody.Builder().apply {
            values.forEach { (key, value) -> add(key, value) }
        }.build()
        val url = APP_API_URL.toHttpUrl().newBuilder(path)!!.build()

        var token = accessToken()
        var response = client.post(url, apiHeaders(token), body, ensureSuccess = false)
        if (response.code == 401) {
            response.close()
            token = refreshAccessToken(force = true)
            response = client.post(url, apiHeaders(token), body, ensureSuccess = false)
        }
        return response.requireSuccess()
    }

    suspend fun verifyLogin(): String {
        refreshAccessToken(force = true)
        return userName.orEmpty()
    }

    fun clearLogin() {
        preferences.edit()
            .remove(PREF_REFRESH_TOKEN)
            .remove(PREF_ACCESS_TOKEN)
            .remove(PREF_TOKEN_EXPIRY)
            .remove(PREF_USER_ID)
            .remove(PREF_USER_NAME)
            .apply()
    }

    private suspend fun accessToken(): String {
        val token = preferences.getString(PREF_ACCESS_TOKEN, "").orEmpty()
        val expiry = preferences.getLong(PREF_TOKEN_EXPIRY, 0)
        return if (token.isNotBlank() && expiry > System.currentTimeMillis() + 60_000) {
            token
        } else {
            refreshAccessToken()
        }
    }

    private suspend fun refreshAccessToken(force: Boolean = false): String = refreshMutex.withLock {
        val cached = preferences.getString(PREF_ACCESS_TOKEN, "").orEmpty()
        val expiry = preferences.getLong(PREF_TOKEN_EXPIRY, 0)
        if (!force && cached.isNotBlank() && expiry > System.currentTimeMillis() + 60_000) {
            return@withLock cached
        }

        val refreshToken = preferences.getString(PREF_REFRESH_TOKEN, "").orEmpty()
        if (refreshToken.isBlank()) {
            throw IOException("Log in to PixEz in extension settings first")
        }

        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("include_policy", "true")
            .build()
        val response = client.post(OAUTH_URL, oauthHeaders(), body, ensureSuccess = false)
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw IOException("Pixiv login failed (HTTP $code). Check the refresh token")
        }
        val token = response.parseAs<OAuthEnvelope>().response
        preferences.edit()
            .putString(PREF_ACCESS_TOKEN, token.accessToken)
            .putString(PREF_REFRESH_TOKEN, token.refreshToken)
            .putLong(PREF_TOKEN_EXPIRY, System.currentTimeMillis() + token.expiresIn * 1000)
            .putString(PREF_USER_ID, token.user.id)
            .putString(PREF_USER_NAME, token.user.name)
            .apply()
        token.accessToken
    }

    private fun apiHeaders(token: String) = commonHeaders().newBuilder()
        .set("Authorization", "Bearer $token")
        .build()

    private fun oauthHeaders(): Headers {
        val time = clientTime()
        return commonHeaders().newBuilder()
            .set("X-Client-Time", time)
            .set("X-Client-Hash", md5(time + HASH_SALT))
            .build()
    }

    private fun commonHeaders() = Headers.Builder()
        .add("User-Agent", "PixivAndroidApp/5.0.166 (Android 13; Pixel 7)")
        .add("App-OS", "Android")
        .add("App-OS-Version", "Android 13")
        .add("App-Version", "5.0.166")
        .add("Accept-Language", acceptLanguage())
        .build()

    private fun acceptLanguage() = when (language()) {
        "zh" -> "zh-CN"
        "zh-tw" -> "zh-TW"
        else -> language()
    }

    private fun clientTime(): String = CLIENT_TIME_FORMAT.format(LocalDateTime.now(Clock.systemUTC()))

    private fun md5(value: String): String = MessageDigest.getInstance("MD5")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun Response.requireSuccess(): Response {
        if (isSuccessful) return this
        val status = code
        close()
        throw IOException("Pixiv API request failed (HTTP $status)")
    }

    companion object {
        internal const val PREF_REFRESH_TOKEN = "refresh_token"
        internal const val PREF_ACCESS_TOKEN = "access_token"
        internal const val PREF_TOKEN_EXPIRY = "token_expiry"
        internal const val PREF_USER_ID = "user_id"
        internal const val PREF_USER_NAME = "user_name"

        private const val APP_API_URL = "https://app-api.pixiv.net"
        private const val OAUTH_URL = "https://oauth.secure.pixiv.net/auth/token"
        private const val HASH_SALT = "28c1fdd1" + "70a52043" + "86cb1313" + "c7077b34" + "f83e4aaf" + "4aa829ce" + "78c231e0" + "5b0bae2c"
        private const val CLIENT_ID = "MOBrBDS8" + "blbauoSc" + "k0ZfDbtu" + "zpyT"
        private const val CLIENT_SECRET = "lsACyCD9" + "4FhDUtGT" + "Xi3QzcFE" + "2uU1hqtD" + "aKeqrdwj"
        private val CLIENT_TIME_FORMAT = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd'T'HH:mm:ss'+00:00'",
            Locale.US,
        ).withZone(ZoneOffset.UTC)
    }
}

internal sealed class Target(val value: String) {
    class Artwork(val id: Long) : Target("artwork:$id")
    class User(val id: Long) : Target("user:$id")
    class Series(val id: Long, val authorId: Long? = null) : Target("series:$id:${authorId.orEmpty()}")
    class BookmarkTag(val restrict: String, val tag: String) : Target("bookmark-tag:$restrict:$tag")

    companion object {
        fun fromValue(value: String): Target? {
            val parts = value.split(':', limit = 3)
            return when (parts.firstOrNull()) {
                "artwork" -> parts.getOrNull(1)?.toLongOrNull()?.let(::Artwork)
                "user" -> parts.getOrNull(1)?.toLongOrNull()?.let(::User)
                "series" -> parts.getOrNull(1)?.toLongOrNull()?.let {
                    Series(it, parts.getOrNull(2)?.toLongOrNull())
                }
                "bookmark-tag" -> parts.getOrNull(1)?.let { restrict ->
                    parts.getOrNull(2)?.let { tag -> BookmarkTag(restrict, tag) }
                }
                else -> null
            }
        }

        fun fromUrl(url: HttpUrl): Target? {
            val path = url.pathSegments.dropWhile { it in PIXIV_LANGUAGES }
            return when {
                path.size >= 2 && path[0] == "artworks" -> path[1].toLongOrNull()?.let(::Artwork)
                path.size >= 4 && path[0] == "user" && path[2] == "series" -> {
                    val authorId = path[1].toLongOrNull()
                    path[3].toLongOrNull()?.let { Series(it, authorId) }
                }
                path.size >= 5 && path[0] == "users" && path[2] == "bookmarks" && path[3] == "artworks" -> {
                    BookmarkTag("public", path[4])
                }
                path.size >= 2 && path[0] == "users" -> path[1].toLongOrNull()?.let(::User)
                else -> null
            }
        }
    }
}

private fun Long?.orEmpty(): String = this?.toString().orEmpty()

private val PIXIV_LANGUAGES = setOf("en", "ja", "zh", "zh-tw", "ko")
