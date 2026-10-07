package eu.kanade.tachiyomi.extension.zh.komiic

import keiyoushi.utils.graphQLBody
import keiyoushi.utils.parseAs
import keiyoushi.utils.toJsonRequestBody
import kotlinx.serialization.Serializable
import okhttp3.Cookie
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.decodeBase64
import java.io.IOException

internal class Account(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
    private val headers: () -> Headers,
) {
    fun hasSession() = client.cookieJar.loadForRequest(baseUrl().toHttpUrl()).any { it.name == ACCESS_TOKEN }

    fun needsRefresh(): Boolean {
        val token = client.cookieJar.loadForRequest(baseUrl().toHttpUrl()).find { it.name == ACCESS_TOKEN } ?: return false
        val expiry = runCatching {
            token.value.split('.').getOrNull(1)?.decodeBase64()?.utf8()?.parseAs<JwtPayload>()?.exp
        }.getOrNull() ?: return true
        return expiry <= System.currentTimeMillis() / 1000 + 3600
    }

    fun login(email: String, password: String) {
        val body = LoginPayload(email, password).toJsonRequestBody()
        client.newCall(Request.Builder().url("${baseUrl()}/api/login").headers(headers()).post(body).build()).execute().use { response ->
            if (!response.isSuccessful) {
                val reason = when (response.code) {
                    400, 401 -> "邮箱、密码或邮箱验证状态不正确"
                    403 -> "网站拒绝登录，请检查邮箱验证状态"
                    429 -> "请求过于频繁，请稍后再试"
                    else -> "网站返回 HTTP ${response.code}"
                }
                throw AccountException("登录失败：$reason")
            }
            // An old cookie must not make an unsuccessful new login look authenticated.
            if (Cookie.parseAll(response.request.url, response.headers).none { it.name == ACCESS_TOKEN && it.value.isNotEmpty() }) {
                throw AccountException("登录失败：网站未返回登录令牌")
            }
        }
        verify()
    }

    fun refresh() {
        client.newCall(Request.Builder().url("${baseUrl()}/auth/refresh").headers(headers()).post(ByteArray(0).toRequestBody()).build()).execute().use { response ->
            if (!response.isSuccessful) throw AccountException("登录已失效：刷新失败（HTTP ${response.code}）")
        }
        verify()
    }

    fun verify() {
        val response = client.newCall(Request.Builder().url("${baseUrl()}/api/query").headers(headers()).post(graphQLBody("{ account { id } }")).build()).execute()
        val id = response.use {
            if (!it.isSuccessful) throw AccountException("登录状态验证失败（HTTP ${it.code}）")
            it.parseAs<AccountResponse>().data?.account?.id
        }
        if (id.isNullOrEmpty() || id == "0") throw AccountException("未登录：官网未确认账号会话")
    }

    fun imageLimit(): ImageLimit {
        val body = graphQLBody("{ getImageLimit { limit usage resetInSeconds } }")
        return client.newCall(Request.Builder().url("${baseUrl()}/api/query").headers(headers()).post(body).build()).execute().use { response ->
            if (!response.isSuccessful) throw AccountException("额度查询失败（HTTP ${response.code}）")
            response.parseAs<LimitResponse>().data?.getImageLimit ?: throw AccountException("网站未返回图片额度")
        }
    }

    private companion object {
        const val ACCESS_TOKEN = "komiic-" + "access-" + "token"
    }
}

internal class AccountException(message: String) : IOException(message)

@Serializable
private class LoginPayload(private val email: String, private val password: String)

@Serializable
private class AccountResponse(val data: AccountData? = null)

@Serializable
private class AccountData(val account: AccountId? = null)

@Serializable
private class AccountId(val id: String)

@Serializable
private class LimitResponse(val data: LimitData? = null)

@Serializable
private class LimitData(val getImageLimit: ImageLimit? = null)

@Serializable
internal class ImageLimit(val limit: Int, val usage: Int, private val resetInSeconds: String = "0") {
    fun summary(): String {
        val resetMinutes = ((resetInSeconds.toLongOrNull() ?: 0) + 59) / 60
        return "已用 $usage / $limit 张；约 $resetMinutes 分钟后重置"
    }
}
