package eu.kanade.tachiyomi.extension.zh.komiic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.addSingleton

class AccountTest {
    private val url = "https://komiic.com".toHttpUrl()
    private val cookies = mutableListOf<Cookie>()
    private val requests = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private val replies = ArrayDeque<Pair<Int, String>>()
    private var loginCookie = false

    private val cookieJar = object : CookieJar {
        override fun loadForRequest(url: HttpUrl) = cookies.filter { it.matches(url) }
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            this@AccountTest.cookies.removeAll { old -> cookies.any { it.name == old.name } }
            this@AccountTest.cookies.addAll(cookies)
        }
    }

    private val client = OkHttpClient.Builder().cookieJar(cookieJar).addInterceptor { chain ->
        val request = chain.request()
        requests.add(request.url.encodedPath)
        bodies.add(Buffer().also { request.body?.writeTo(it) }.readUtf8())
        val (code, body) = replies.removeFirst()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType()))
            .apply {
                if (loginCookie && request.url.encodedPath == "/api/login") {
                    header("Set-Cookie", "komiic-access-token=fixture; Path=/; Secure")
                }
            }.build()
        cookieJar.saveFromResponse(request.url, Cookie.parseAll(request.url, response.headers))
        response
    }.build()

    private val account = Account(client, { url.toString().removeSuffix("/") }, { Headers.Builder().build() })

    @Test
    fun successfulHttpWithoutNewCookieDoesNotAcceptOldSession() {
        cookies.add(Cookie.Builder().name("komiic-access-token").value("old").domain(url.host).build())
        replies.add(200 to "{}")
        val error = org.junit.Assert.assertThrows(AccountException::class.java) { account.login("fixture@example.invalid", "fixture") }
        assertTrue(error.message!!.contains("未返回登录令牌"))
        assertEquals(listOf("/api/login"), requests)
    }

    @Test
    fun cookieAloneDoesNotConfirmLogin() {
        loginCookie = true
        replies.add(200 to "{}")
        replies.add(200 to """{"errors":[{"message":"no token"}],"data":null}""")
        org.junit.Assert.assertThrows(AccountException::class.java) { account.login("fixture@example.invalid", "fixture") }
        assertEquals(listOf("/api/login", "/api/query"), requests)
    }

    @Test
    fun loginChecksAccountAndEscapesCredentials() {
        loginCookie = true
        replies.add(200 to "{}")
        replies.add(200 to """{"data":{"account":{"id":"123"}}}""")
        val input = "quote\"slash\\line\n"
        account.login("fixture@example.invalid", input)
        assertEquals(input, Json.parseToJsonElement(bodies.first()).jsonObject["password"]!!.jsonPrimitive.content)
        assertEquals(listOf("/api/login", "/api/query"), requests)
    }

    @Test
    fun accountZeroIsAnonymous() {
        replies.add(200 to """{"data":{"account":{"id":"0"}}}""")
        org.junit.Assert.assertThrows(AccountException::class.java) { account.verify() }
    }

    @Test
    fun failedLoginDoesNotExposeResponseOrCredentials() {
        replies.add(401 to """{"error":"untrusted fixture response"}""")
        val error = org.junit.Assert.assertThrows(AccountException::class.java) { account.login("fixture@example.invalid", "fixture") }
        assertEquals("登录失败：邮箱、密码或邮箱验证状态不正确", error.message)
    }

    @Test
    fun imageQuotaShowsUsageAndReset() {
        replies.add(200 to """{"data":{"getImageLimit":{"limit":300,"usage":300,"resetInSeconds":"61"}}}""")
        assertEquals("已用 300 / 300 张；约 2 分钟后重置", account.imageLimit().summary())
    }

    @Test
    fun invalidTokenRequiresRefresh() {
        cookies.add(Cookie.Builder().name("komiic-access-token").value("invalid").domain(url.host).build())
        assertTrue(account.needsRefresh())
    }

    @Test
    fun unpaddedUrlSafeTokenKeepsUnexpiredSession() {
        val payload = """{"exp":4102444800,"fixture":"~~~???"}""".encodeUtf8().base64Url().trimEnd('=')
        assertTrue(payload.contains('-') && payload.contains('_'))
        cookies.add(Cookie.Builder().name("komiic-access-token").value("header.$payload.signature").domain(url.host).build())
        assertFalse(account.needsRefresh())
    }

    @Test
    fun tokenNearExpiryRequiresRefresh() {
        val expiry = System.currentTimeMillis() / 1000 + 1800
        val payload = """{"exp":$expiry}""".encodeUtf8().base64Url().trimEnd('=')
        cookies.add(Cookie.Builder().name("komiic-access-token").value("header.$payload.signature").domain(url.host).build())
        assertTrue(account.needsRefresh())
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUp() {
            Injekt.addSingleton(Json { ignoreUnknownKeys = true })
        }
    }
}
