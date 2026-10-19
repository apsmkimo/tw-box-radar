package com.twboxradar.data

import android.content.Context
import com.twboxradar.BuildConfig
import com.twboxradar.core.Bar
import com.twboxradar.core.SymbolHistory
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
)

class MarketRepository(private val context: Context) {
    suspend fun loadMarket(force: Boolean): LoadedMarket = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val cacheManifest = File(context.filesDir, "manifest.json")
        val cacheWindow = File(context.filesDir, "window.json")
        try {
            val manifestText = http("${BuildConfig.DATA_BASE_URL}/manifest.json")
            val manifest = JSONObject(manifestText)
            val asOf = manifest.optString("asOf")
            val cachedAsOf = if (cacheManifest.exists()) JSONObject(cacheManifest.readText()).optString("asOf") else ""
            val windowText = if (!force && cachedAsOf == asOf && cacheWindow.exists()) {
                cacheWindow.readText()
            } else {
                http("${BuildConfig.DATA_BASE_URL}/window.json").also { body ->
                    cacheManifest.writeText(manifestText)
                    cacheWindow.writeText(body)
                }
            }
            LoadedMarket(parseWindow(windowText), asOf, sample = false, message = null)
        } catch (error: Exception) {
            if (cacheWindow.exists()) {
                val asOf = if (cacheManifest.exists()) JSONObject(cacheManifest.readText()).optString("asOf") else ""
                LoadedMarket(
                    parseWindow(cacheWindow.readText()),
                    asOf,
                    sample = false,
                    message = "更新失敗，顯示上次下載的資料",
                )
            } else {
                val text = context.assets.open("sample/window.json").bufferedReader().use { it.readText() }
                LoadedMarket(
                    parseWindow(text),
                    JSONObject(text).optString("asOf"),
                    sample = true,
                    message = "連線失敗，顯示內建範例。合併到 main 並完成每日發佈後會改為全市場資料",
                )
            }
        }
    }

    suspend fun loadHistory(code: String, fallback: SymbolHistory?): SymbolHistory? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val file = File(context.filesDir, "bars/$code.json")
            try {
                val text = http("${BuildConfig.DATA_BASE_URL}/bars/$code.json")
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

    private fun http(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "tw-box-radar-android/0.1")
            setRequestProperty("Accept", "application/json")
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
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
