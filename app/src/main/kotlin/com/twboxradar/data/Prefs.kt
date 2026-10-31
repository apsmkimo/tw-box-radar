package com.twboxradar.data

import android.content.Context
import com.twboxradar.core.BoxParams

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("tw_box_radar", Context.MODE_PRIVATE)

    var disclaimerAccepted: Boolean
        get() = sp.getBoolean(KEY_DISCLAIMER, false)
        set(value) = sp.edit().putBoolean(KEY_DISCLAIMER, value).apply()

    var alertsEnabled: Boolean
        get() = sp.getBoolean(KEY_ALERTS, true)
        set(value) = sp.edit().putBoolean(KEY_ALERTS, value).apply()

    var watchlist: Set<String>
        get() = sp.getStringSet(KEY_WATCH, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_WATCH, value.toSet()).apply()

    var notified: Set<String>
        get() = sp.getStringSet(KEY_NOTIFIED, emptySet())?.toSet() ?: emptySet()
        set(value) = sp.edit().putStringSet(KEY_NOTIFIED, value.toSet()).apply()

    // SMCPKG_SUPPORT>>>CursorXXX
    /** 空白表示 main。合併前可改成預覽分支，先下載全市場。 */
    var dataBranch: String
        get() = sp.getString(KEY_BRANCH, "") ?: ""
        set(value) = sp.edit().putString(KEY_BRANCH, value.trim()).apply()
    // SMCPKG_SUPPORT<<<CursorYYY

    fun boxParams(): BoxParams = BoxParams(
        lookbackDays = sp.getInt(KEY_LOOKBACK, 60),
        confirmationDays = sp.getInt(KEY_CONFIRM, 3),
        useWicks = sp.getBoolean(KEY_WICKS, true),
        nearRangeFraction = sp.getFloat(KEY_NEAR, 0.2f).toDouble(),
        volumeMultiple = sp.getFloat(KEY_VOLUME, 1.5f).toDouble(),
        requireVolumeForEntry = sp.getBoolean(KEY_VOLUME_ENTRY, false),
        skipLimitUpEntry = sp.getBoolean(KEY_SKIP_LIMIT_UP, false),
    )

    fun saveParams(params: BoxParams) {
        sp.edit()
            .putInt(KEY_LOOKBACK, params.lookbackDays)
            .putInt(KEY_CONFIRM, params.confirmationDays)
            .putBoolean(KEY_WICKS, params.useWicks)
            .putFloat(KEY_NEAR, params.nearRangeFraction.toFloat())
            .putFloat(KEY_VOLUME, params.volumeMultiple.toFloat())
            .putBoolean(KEY_VOLUME_ENTRY, params.requireVolumeForEntry)
            .putBoolean(KEY_SKIP_LIMIT_UP, params.skipLimitUpEntry)
            .apply()
    }

    companion object {
        private const val KEY_DISCLAIMER = "disclaimer"
        private const val KEY_ALERTS = "alerts"
        private const val KEY_WATCH = "watch"
        private const val KEY_NOTIFIED = "notified"
        private const val KEY_LOOKBACK = "lookback"
        private const val KEY_CONFIRM = "confirm"
        private const val KEY_WICKS = "wicks"
        private const val KEY_NEAR = "near"
        private const val KEY_VOLUME = "volume"
        private const val KEY_VOLUME_ENTRY = "volume_entry"
        private const val KEY_BRANCH = "data_branch"
        private const val KEY_SKIP_LIMIT_UP = "skip_limit_up"
    }
}
