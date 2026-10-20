package com.twboxradar.data

import android.content.Context
import com.twboxradar.core.Bar
import com.twboxradar.core.PREVIEW_DATA_BRANCH
import com.twboxradar.core.SymbolHistory
import com.twboxradar.core.marketDocsUrl
import com.twboxradar.core.normalizeDataBranch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class LoadedMarket(
    val symbols: List<SymbolHistory>,
    val asOf: String,
    val sample: Boolean,
    val message: String?,
    val sourceUrl: String = "",
)

class MarketRepository(private val context: Context) {
    // SMCPKG_SUPPORT>>>CursorXXX
    // suspend fun loadMarket(force: Boolean): LoadedMarket
    // 網址以前寫死 BuildConfig.DATA_BASE_URL（main）。main 還沒合併時會 404，只能看範例。
    suspend fun loadMarket(force: Boolean, branch: String): LoadedMarket = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val base = marketDocsUrl(branch)
        val cacheManifest = cacheFile(branch, "manifest.json")
        val cacheWindow = cacheFile(branch, "window.json")
        try {
            val manifestText = http("$base/manifest.json")
            val manifest = JSONObject(manifestText)
            val asOf = manifest.optString("asOf")
            val cachedAsOf = if (cacheManifest.exists()) JSONObject(cacheManifest.readText()).optString("asOf") else ""
            val windowText = if (!force && cachedAsOf == asOf && cacheWindow.exists()) {
                cacheWindow.readText()
            } else {
                http("$base/window.json").also { body ->
                    cacheManifest.parentFile?.mkdirs()
                    cacheManifest.writeText(manifestText)
                    cacheWindow.writeText(body)
                }
            }
            LoadedMarket(parseWindow(windowText), asOf, sample = false, message = null, sourceUrl = base)
        } catch (error: Exception) {
            val reason = error.message ?: error.javaClass.simpleName
            if (cacheWindow.exists()) {
                val asOf = if (cacheManifest.exists()) JSONObject(cacheManifest.readText()).optString("asOf") else ""
                LoadedMarket(
                    parseWindow(cacheWindow.readText()),
                    asOf,
                    sample = false,
                    message = "更新失敗（$reason）。顯示這個分支上次下載的資料。",
                    sourceUrl = base,
                )
            } else {
                val text = context.assets.open("sample/window.json").bufferedReader().use { it.readText() }
                val hint = if (normalizeDataBranch(branch) == "main") {
                    "main 要等合併才有檔案。到「設定」把資料分支改成 $PREVIEW_DATA_BRANCH，再按重試，就可以先看全市場。"
                } else {
                    "請確認分支名稱後按重試。"
                }
                LoadedMarket(
                    parseWindow(text),
                    JSONObject(text).optString("asOf"),
                    sample = true,
                    message = "連不到 $base/manifest.json（$reason）。目前顯示內建範例。$hint",
                    sourceUrl = base,
                )
            }
        }
    }

    // suspend fun loadHistory(code: String, fallback: SymbolHistory?): SymbolHistory?
    suspend fun loadHistory(code: String, fallback: SymbolHistory?, branch: String): SymbolHistory? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = cacheFile(branch, "bars/$code.json")
            try {
                val text = http("${marketDocsUrl(branch)}/bars/$code.json")
                file.parentFile?.mkdirs()
                file.writeText(text)
                parseSymbol(JSONObject(text))
            } catch (error: Exception) {
                when {
                    file.exists() -> parseSymbol(JSONObject(file.readText()))
                    else -> fallback
                }
            }
        }

    private fun cacheFile(branch: String, name: String): File {
        val folder = normalizeDataBranch(branch).replace('/', '_')
        return File(context.filesDir, "market/$folder/$name")
    }
    // SMCPKG_SUPPORT<<<CursorYYY

    private fun http(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 180_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "tw-box-radar-android/0.1")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                // SMCPKG_SUPPORT>>>CursorXXX
                // throw IllegalStateException("HTTP $code")
                throw IllegalStateException("HTTP $code $url")
                // SMCPKG_SUPPORT<<<CursorYYY
            }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

fun parseWindow(text: String): List<SymbolHistory> {
    val root = JSONObject(text)
    val array = root.getJSONArray("symbols")
    return buildList {
        for (i in 0 until array.length()) {
            add(parseSymbol(array.getJSONObject(i)))
        }
    }
}

fun parseSymbol(obj: JSONObject): SymbolHistory {
    // SMCPKG_SUPPORT>>>CursorXXX
    // val barsJson = obj.getJSONArray("bars")
    // val bars = buildList { ... 只讀 bars ... }
    // return SymbolHistory(code = obj.getString("c"), name = obj.getString("n"), market = obj.getString("m"), bars = bars)
    val bars = readBars(obj.getJSONArray("bars"))
    val rawBars = if (obj.has("raw") && !obj.isNull("raw")) readBars(obj.getJSONArray("raw")) else emptyList()
    return SymbolHistory(
        code = obj.getString("c"),
        name = obj.getString("n"),
        market = obj.getString("m"),
        bars = bars,
        rawBars = rawBars,
    )
    // SMCPKG_SUPPORT<<<CursorYYY
}

private fun readBars(barsJson: JSONArray): List<Bar> {
    return buildList {
        for (i in 0 until barsJson.length()) {
            val bar = barsJson.getJSONArray(i)
            add(
                Bar(
                    date = bar.getInt(0),
                    open = bar.getDouble(1),
                    high = bar.getDouble(2),
                    low = bar.getDouble(3),
                    close = bar.getDouble(4),
                    volume = bar.getLong(5),
                ),
            )
        }
    }
}
