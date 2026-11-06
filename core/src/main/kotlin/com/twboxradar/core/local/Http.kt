package com.twboxradar.core.local

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** HTTP 回應。body 已解壓並以 UTF-8 解碼，去掉 BOM。 */
class HttpResponse(val code: Int, val body: String) {
    val ok: Boolean get() = code in 200..299
}

/** 可替換的 GET，單元測試用假的回應。網路錯誤丟出 IOException。 */
fun interface HttpGet {
    fun get(url: String, headers: Map<String, String>): HttpResponse
}

/** 證交所與櫃買會擋太頻繁或沒有瀏覽器標頭的請求，直連時用一般瀏覽器的 User-Agent。 */
const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

const val APP_USER_AGENT = "tw-box-radar-android/0.3"

/** java.net 實作。自己帶 Accept-Encoding: gzip 並自己解壓，Android 與 JVM 行為一致。 */
object UrlConnectionHttp : HttpGet {
    override fun get(url: String, headers: Map<String, String>): HttpResponse {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json,text/plain,*/*")
            setRequestProperty("Accept-Encoding", "gzip")
            headers.forEach { (key, value) -> setRequestProperty(key, value) }
        }
        try {
            val code = connection.responseCode
            val stream = (if (code >= 400) connection.errorStream else connection.inputStream)
                ?: return HttpResponse(code, "")
            val input = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) GZIPInputStream(stream) else stream
            val body = input.use { it.readBytes() }.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            return HttpResponse(code, body)
        } catch (error: IOException) {
            throw error
        } finally {
            connection.disconnect()
        }
    }
}

/**
 * 直連交易所用的節流器：任兩個請求至少間隔 minIntervalMs（預設 3 秒），失敗重試一次。
 * sleep 可替換，單元測試不真的等待。
 */
class ThrottledHttp(
    private val http: HttpGet,
    private val minIntervalMs: Long = 3_000L,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val userAgent: String = BROWSER_USER_AGENT,
) {
    private var last = 0L
    var requests = 0
        private set

    fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponse {
        var lastError: IOException? = null
        repeat(2) {
            pause()
            requests++
            try {
                val response = http.get(url, mapOf("User-Agent" to userAgent) + headers)
                if (response.ok || response.code in 400..499) return response
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("HTTP 失敗：$url")
    }

    private fun pause() {
        val now = clock()
        val wait = last + minIntervalMs - now
        if (last > 0 && wait > 0) sleep(wait)
        last = clock()
    }
}
