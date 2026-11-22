package com.shortfeed.app

import android.content.Context
import android.content.SharedPreferences

/** Persists the picker selection so re-launching skips reconfiguration. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.getSharedPreferences("shortfeed", Context.MODE_PRIVATE)

    fun load(): FeedConfig {
        val enabled = sp.getStringSet(KEY_ENABLED, setOf("bilibili"))?.toList() ?: listOf("bilibili")
        val url = sp.getString(KEY_URL, "").orEmpty()
        return FeedConfig(enabled, url)
    }

    fun save(cfg: FeedConfig) {
        sp.edit()
            .putStringSet(KEY_ENABLED, cfg.enabled.toSet())
            .putString(KEY_URL, cfg.backendUrl)
            .apply()
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "backend_url"
    }
}
