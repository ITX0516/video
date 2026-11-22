package com.shortfeed.app

import android.content.Context
import android.content.SharedPreferences
import com.shortfeed.app.data.Platform
import com.shortfeed.app.data.VideoItem
import org.json.JSONObject

/** Persists the picker selection, login cookie, local likes/favourites and player prefs. */
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

    /** Preferred Bilibili quality (qn). 64 = 720P. */
    fun loadBiliQuality(): Int = sp.getInt(KEY_BILI_QN, 64)

    fun saveBiliQuality(qn: Int) {
        sp.edit().putInt(KEY_BILI_QN, qn).apply()
    }

    /** Preferred Douyin rendition label ("1080P"…). Empty = the feed's default address. */
    fun loadDouyinQuality(): String = sp.getString(KEY_DY_Q, "").orEmpty()

    fun saveDouyinQuality(label: String) {
        sp.edit().putString(KEY_DY_Q, label).apply()
    }

    // ---- local likes / favourites ----

    fun isLiked(item: VideoItem): Boolean = contains(KEY_LIKES, keyOf(item))

    fun isFavorite(item: VideoItem): Boolean = contains(KEY_FAVS, keyOf(item))

    /** Toggles the local like and remembers enough metadata to list / replay it later. */
    fun toggleLike(item: VideoItem): Boolean = toggle(KEY_LIKES, KEY_LIKE_META, item)

    fun toggleFavorite(item: VideoItem): Boolean = toggle(KEY_FAVS, KEY_FAV_META, item)

    fun likedItems(): List<VideoItem> = readMeta(KEY_LIKE_META)

    fun favoriteItems(): List<VideoItem> = readMeta(KEY_FAV_META)

    private fun keyOf(item: VideoItem): String = "${item.platform.name}:${item.id}"

    private fun contains(setKey: String, key: String): Boolean =
        sp.getStringSet(setKey, emptySet()).orEmpty().contains(key)

    private fun toggle(setKey: String, metaKey: String, item: VideoItem): Boolean {
        val key = keyOf(item)
        val ids = sp.getStringSet(setKey, emptySet()).orEmpty().toMutableSet()
        val nowOn = if (!ids.add(key)) {
            ids.remove(key)
            false
        } else {
            true
        }
        val meta = JSONObject(sp.getString(metaKey, "{}").orEmpty())
        if (nowOn) {
            meta.put(
                key,
                JSONObject()
                    .put("id", item.id)
                    .put("platform", item.platform.name)
                    .put("title", item.title)
                    .put("author", item.author)
                    .put("cover", item.cover)
                    .put("url", item.playUrl)
                    .put("at", System.currentTimeMillis()),
            )
        } else {
            meta.remove(key)
        }
        sp.edit()
            .putStringSet(setKey, ids)
            .putString(metaKey, meta.toString())
            .apply()
        return nowOn
    }

    private fun readMeta(metaKey: String): List<VideoItem> {
        val meta = JSONObject(sp.getString(metaKey, "{}").orEmpty())
        val out = mutableListOf<Pair<Long, VideoItem>>()
        val keys = meta.keys()
        while (keys.hasNext()) {
            val obj = meta.optJSONObject(keys.next()) ?: continue
            val platform = runCatching { Platform.valueOf(obj.optString("platform")) }
                .getOrDefault(Platform.BILIBILI)
            out += obj.optLong("at") to VideoItem(
                id = obj.optString("id"),
                platform = platform,
                title = obj.optString("title"),
                author = obj.optString("author"),
                cover = obj.optString("cover"),
                playUrl = obj.optString("url"),
                sourceKey = "library",
            )
        }
        return out.sortedByDescending { it.first }.map { it.second }
    }

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_URL = "backend_url"
        private const val KEY_SCALE = "scale_mode"
        private const val KEY_BILI_SESS = "bili_sessdata"
        private const val KEY_BILI_QN = "bili_qn"
        private const val KEY_DY_Q = "douyin_quality"
        private const val KEY_LIKES = "liked_ids"
        private const val KEY_FAVS = "fav_ids"
        private const val KEY_LIKE_META = "liked_meta"
        private const val KEY_FAV_META = "fav_meta"
    }
}
