package com.twboxradar.data

import android.content.Context
import com.twboxradar.core.BoxParams
import com.twboxradar.core.local.DataSourceMode

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

    /** 提醒最後處理過的資料日（manifest asOf）。同一個資料日不重複下載、不重複通知。 */
    var lastAlertAsOf: String
        get() = sp.getString(KEY_LAST_ALERT_AS_OF, "") ?: ""
        set(value) = sp.edit().putString(KEY_LAST_ALERT_AS_OF, value).apply()

    /** 資料來源：預設遠端（Cloudflare）。以前的「資料分支」設定已移除。 */
    var dataSourceMode: DataSourceMode
        get() = if (sp.getString(KEY_SOURCE, "") == "direct") DataSourceMode.DIRECT else DataSourceMode.REMOTE
        set(value) = sp.edit().putString(KEY_SOURCE, if (value == DataSourceMode.DIRECT) "direct" else "remote").apply()

    var removeAdsOwned: Boolean
        get() = sp.getBoolean(KEY_REMOVE_ADS, false)
        set(value) = sp.edit().putBoolean(KEY_REMOVE_ADS, value).apply()

    var debugRemoveAds: Boolean
        get() = sp.getBoolean(KEY_DEBUG_REMOVE_ADS, false)
        set(value) = sp.edit().putBoolean(KEY_DEBUG_REMOVE_ADS, value).apply()

    var appOpenShownAt: Long
        get() = sp.getLong(KEY_APP_OPEN_AT, 0L)
        set(value) = sp.edit().putLong(KEY_APP_OPEN_AT, value).apply()

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
        private const val KEY_SOURCE = "data_source"
        private const val KEY_REMOVE_ADS = "remove_ads"
        private const val KEY_DEBUG_REMOVE_ADS = "debug_remove_ads"
        private const val KEY_APP_OPEN_AT = "app_open_at"
        private const val KEY_LAST_ALERT_AS_OF = "last_alert_as_of"
        private const val KEY_SKIP_LIMIT_UP = "skip_limit_up"
    }
}
