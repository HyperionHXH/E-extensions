package eu.kanade.tachiyomi.extension.en.ehentai

import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException

/** Retry rules shared by page requests and H@H image requests. */
internal object EhentaiRetryPolicy {
    private const val MAX_RETRY_DELAY_MS = 15_000L
    private val permanentBlockMarkers = listOf(
        "temporarily banned",
        "permanently banned",
        "your ip address has been banned",
        "your ip has been banned",
        "ip address has been banned",
        "excessive request rate",
        "access denied",
    )

    fun isRetryableImageResponse(code: Int, body: String = ""): Boolean = when {
        code == 403 -> !isExplicitlyBlocked(body)
        code == 404 || code == 429 || code >= 500 -> true
        else -> false
    }

    fun isExplicitlyBlocked(body: String): Boolean {
        val normalized = body.lowercase()
        return permanentBlockMarkers.any(normalized::contains)
    }

    fun isRetryableNetworkFailure(error: Throwable): Boolean {
        if (error.message.equals("Canceled", ignoreCase = true)) return false

        val causes = generateSequence(error) { it.cause }.toList()
        if (causes.any { it is UnknownHostException }) return false
        if (causes.any { it is ConnectException && it.message.isConnectionRefused() }) return false
        return error is IOException
    }

    fun isRetryablePageFailure(error: Throwable): Boolean {
        if (isRetryableNetworkFailure(error)) return true
        return Regex("HTTP(?: error)? (?:429|5\\d{2})").containsMatchIn(error.message.orEmpty())
    }

    fun retryDelayMs(retryNumber: Int): Long {
        val exponent = (retryNumber - 1).coerceAtLeast(0).coerceAtMost(5)
        return (500L shl exponent).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private fun String?.isConnectionRefused(): Boolean = this?.contains("connection refused", ignoreCase = true) == true ||
        this?.contains("actively refused", ignoreCase = true) == true
}
