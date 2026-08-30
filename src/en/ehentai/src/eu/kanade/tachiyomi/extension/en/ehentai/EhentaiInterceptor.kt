package eu.kanade.tachiyomi.extension.en.ehentai

import eu.kanade.tachiyomi.network.GET
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.IOException
import javax.net.ssl.SSLHandshakeException

/**
 * Adds the configured browser User-Agent to every request.
 *
 * Referer headers are set at request-construction time (page requests use
 * `baseUrl/`, image requests use the viewer page URL) because the two kinds
 * of requests are built by different code paths.
 */
class EhentaiInterceptor(
    private val prefs: EhentaiPreferences,
    private val fallbackClient: OkHttpClient? = null,
    private val onImageUrlRefreshed: ((viewerUrl: String, imageUrl: String) -> Unit)? = null,
) : Interceptor {

    companion object {
        /** Internal request context used to refresh a failed H@H image URL. */
        const val VIEWER_URL_HEADER = "X-Ehentai-Viewer-Url"
        private const val MAX_IMAGE_RETRIES = 3
        private val RELOAD_KEY_REGEX = Regex("""return\s+nl\(['\"]([^'\"]+)['\"]\)""")
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder()

        builder.header("User-Agent", prefs.userAgent)

        var requestWithHeaders = builder.build()
        val retryableImageHost = isImageHost(requestWithHeaders.url.host)
        val viewerUrl = request.header(VIEWER_URL_HEADER) ?: request.header("Referer")
        var attempt = 0
        while (true) {
            try {
                val response = chain.proceed(requestWithHeaders)
                if (retryableImageHost && attempt < MAX_IMAGE_RETRIES && isRetryableImageStatus(response.code)) {
                    response.close()
                    attempt++
                    refreshImageUrl(viewerUrl, preferOriginal = attempt == MAX_IMAGE_RETRIES)?.let { refreshedUrl ->
                        viewerUrl?.let { onImageUrlRefreshed?.invoke(it, refreshedUrl) }
                        requestWithHeaders = requestWithHeaders.newBuilder()
                            .url(refreshedUrl)
                            .removeHeader(VIEWER_URL_HEADER)
                            .build()
                        continue
                    }
                    Thread.sleep(250L * attempt)
                    continue
                }
                return response
            } catch (e: IOException) {
                if (!retryableImageHost || attempt >= MAX_IMAGE_RETRIES) throw e
                attempt++
                val preferOriginal = e.isTlsHandshakeFailure() || attempt == MAX_IMAGE_RETRIES
                refreshImageUrl(viewerUrl, preferOriginal)?.let { refreshedUrl ->
                    viewerUrl?.let { onImageUrlRefreshed?.invoke(it, refreshedUrl) }
                    requestWithHeaders = requestWithHeaders.newBuilder()
                        .url(refreshedUrl)
                        .removeHeader(VIEWER_URL_HEADER)
                        .build()
                    continue
                }
                Thread.sleep(250L * attempt)
            }
        }
    }

    private fun isRetryableImageStatus(code: Int): Boolean = code == 403 || code == 404 || code == 429 || code >= 500

    private fun isImageHost(host: String): Boolean = host == "hath.network" || host.endsWith(".hath.network") ||
        host == "ehgt.org" || host.endsWith(".ehgt.org")

    /**
     * Hath URLs expire and can also point at a temporarily unavailable node.
     * E-Hentai exposes the JavaScript `nl(key)` reload endpoint for exactly this
     * case; fetch it without the extension interceptor, then retry the image.
     */
    private fun refreshImageUrl(viewerUrl: String?, preferOriginal: Boolean): String? {
        val client = fallbackClient ?: return null
        if (viewerUrl.isNullOrBlank()) return null
        return try {
            val headers = Headers.Builder()
                .add("User-Agent", prefs.userAgent)
                .add("Referer", "${viewerUrl.toHttpUrl().scheme}://${viewerUrl.toHttpUrl().host}/")
                .apply {
                    prefs.cookie.takeIf { it.isNotEmpty() }?.let { add("Cookie", it) }
                }
                .build()
            val viewerHtml = client.newCall(GET(viewerUrl, headers)).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body.string()
            }
            val viewerDocument = Jsoup.parse(viewerHtml, viewerUrl)
            if (preferOriginal) {
                return runCatching { parseImageUrl(viewerDocument, wantOriginal = true) }.getOrNull()
            }
            val reloadKey = RELOAD_KEY_REGEX.find(viewerHtml)?.groupValues?.get(1) ?: return null
            val reloadUrl = viewerUrl.toHttpUrl().newBuilder()
                .addQueryParameter("nl", reloadKey)
                .build()
                .toString()
            val reloadHtml = client.newCall(GET(reloadUrl, headers)).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body.string()
            }
            Jsoup.parse(reloadHtml, reloadUrl).selectFirst("img#img[src]")?.absUrl("src")
        } catch (_: Exception) {
            null
        }
    }

    private fun IOException.isTlsHandshakeFailure(): Boolean = generateSequence<Throwable>(this) { it.cause }.any { it is SSLHandshakeException }
}
