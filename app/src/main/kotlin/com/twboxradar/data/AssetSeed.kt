package com.twboxradar.data

import android.content.Context
import com.twboxradar.core.local.LocalStore
import com.twboxradar.core.local.SeedSource
import java.io.File

/**
 * APK 內建的初始資料：assets/seed/ 底下放和本機 LocalStore 相同的目錄結構
 * （q/YYYYMMDD.gz、c/YYYYMMDD.gz、names.tsv、exrights.tsv、revenue.tsv）。
 * 由 pipeline/make_seed.py 產生。目前 APK 還沒有打包，available() 回 false，改走 GitHub 匯入。
 */
class AssetSeed(private val context: Context) : SeedSource {
    override val label: String = "APK 內建歷史資料"

    override fun available(): Boolean =
        runCatching { context.assets.list("$ROOT/q")?.isNotEmpty() == true }.getOrDefault(false)

    override fun seed(store: LocalStore, progress: (String) -> Unit): Int {
        progress("匯入內建歷史資料")
        val temp = File(context.cacheDir, "seed-import")
        temp.deleteRecursively()
        try {
            copyTree(ROOT, temp)
            return store.importFrom(LocalStore(temp))
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun copyTree(asset: String, target: File) {
        val children = context.assets.list(asset).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) copyTree("$asset/$child", File(target, child))
    }

    private companion object {
        const val ROOT = "seed"
    }
}
