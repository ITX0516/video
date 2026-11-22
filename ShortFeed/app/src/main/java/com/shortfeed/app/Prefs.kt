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

    /** Persisted picture mode name (ScaleMode.name); null = use player default. */
    fun loadScaleMode(): String? = sp.getString(KEY_SCALE, null)

    fun saveScaleMode(name: String) {
        sp.edit().putString(KEY_SCALE, name).apply()
    }

    /** Bilibili SESSDATA cookie, obtained from the in-app login page. Empty when not logged in. */
    fun loadBiliSession(): String = sp.getString(KEY_BILI_SESS, "").orEmpty()

    fun saveBiliSession(sessdata: String) {
        sp.edit().putString(KEY_BILI_SESS, sessdata).apply()
    }

    /** Local like state, keyed by "PLATFORM:videoId". Returns true when it is now liked. */
    fun toggleLike(key: String): Boolean = toggle(KEY_LIKES, key)

    fun isLiked(key: String): Boolean = sp.getStringSet(KEY_LIKES, emptySet()).orEmpty().contains(key)

    /** Local favourite state. Returns true when it is now favourited. */
    fun toggleFavorite(key: String): Boolean = toggle(KEY_FAVS, key)

    fun isFavorite(key: String): Boolean = sp.getStringSet(KEY_FAVS, emptySet()).orEmpty().contains(key)

    private fun toggle(prefKey: String, key: String): Boolean {
        val cur = sp.getStringSet(prefKey, emptySet()).orEmpty().toMutableSet()
        val nowOn = if (!cur.add(key)) {
            cur.remove(key)
            false
        } else {
            true
        }
        sp.edit().putStringSet(prefKey, cur).apply()
        return nowOn
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "backend_url"
        private const val KEY_SCALE = "scale_mode"
        private const val KEY_BILI_SESS = "bili_sessdata"
        private const val KEY_LIKES = "liked_ids"
        private const val KEY_FAVS = "fav_ids"
    }
}
